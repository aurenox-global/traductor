#!/usr/bin/env python3
"""Valida EN->ES de un texto LARGO contra el modelo real (Qwen3.5-0.8B Q4) con
llama-server, usando la MISMA lógica de troceo y los MISMOS prompts que la app
Android (ver TextChunker.kt y Prompts.kt).

- "VIEJO": una sola petición con max_tokens=256 (comportamiento del bug) -> se trunca.
- "NUEVO": troceo por párrafos->frases->palabras + max_tokens dinámico
  (~min(768, max(192, chars/2))) + n_ctx=4096 -> traducción completa.

Requiere `llama-server` en el PATH y el modelo en ~/models.
Uso: python3 scripts/validate_long_translation.py
"""
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

MODEL = os.path.expanduser("~/models/Qwen3.5-0.8B-Q4_K_M.gguf")
PORT = 18080
HOST = "127.0.0.1"
N_CTX = 4096                      # igual que TranslationPipeline.MT_N_CTX
OLD_MAX_TOKENS = 256              # comportamiento previo (bug)
MAX_OUTPUT_TOKENS = 768
MIN_OUTPUT_TOKENS = 192
MAX_CHARS_PER_CHUNK = 1200        # TextChunker.DEFAULT_MAX_CHARS

# ---- Port fiel del chunker (TextChunker.kt) --------------------------------
PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")
SENTENCE_SPLIT = re.compile(r"(?<=[.!?…。！？])\s+")
WHITESPACE = re.compile(r"\s+")


def _split_by_words(sentence, max_chars):
    words = [w for w in WHITESPACE.split(sentence) if w]
    out, sb = [], ""
    for w in words:
        if len(w) > max_chars:
            if sb:
                out.append(sb); sb = ""
            i = 0
            while i < len(w):
                out.append(w[i:i + max_chars]); i += max_chars
            continue
        if not sb:
            sb = w
        elif len(sb) + 1 + len(w) <= max_chars:
            sb = sb + " " + w
        else:
            out.append(sb); sb = w
    if sb:
        out.append(sb)
    return out


def _append_units(out, line, first_sep, max_chars):
    if len(line) <= max_chars:
        out.append((line, first_sep)); return
    sentences = [s.strip() for s in SENTENCE_SPLIT.split(line) if s.strip()]
    first = True
    for s in sentences:
        sep = first_sep if first else " "
        if len(s) <= max_chars:
            out.append((s, sep))
        else:
            fw = True
            for wp in _split_by_words(s, max_chars):
                out.append((wp, sep if fw else " "))
                fw = False
        first = False


def chunk(text, max_chars=MAX_CHARS_PER_CHUNK):
    if not text:
        return []
    if len(text) <= max_chars:
        return [text]
    blocks = []
    for pi, p in enumerate([p for p in PARAGRAPH_SPLIT.split(text) if p.strip()]):
        for li, raw in enumerate(p.split("\n")):
            line = raw.strip()
            if not line:
                continue
            first_sep = "\n" if li > 0 else ("\n\n" if pi > 0 else "")
            _append_units(blocks, line, first_sep, max_chars)
    chunks, sb = [], ""
    for text_b, sepbefore in blocks:
        piece = text_b.strip()
        if not piece:
            continue
        if not sb:
            sb = piece
        else:
            sep = sepbefore if sepbefore else " "
            if len(sb) + len(sep) + len(piece) <= max_chars:
                sb = sb + sep + piece
            else:
                chunks.append(sb); sb = piece
    if sb:
        chunks.append(sb)
    return chunks


# ---- Prompts (idénticos a Prompts.kt) --------------------------------------
def system_prompt(target_code="es", source_code="en"):
    target = "español" if target_code == "es" else target_code
    source = "inglés" if source_code == "en" else source_code
    return (
        f"Eres un traductor profesional. Traduce del {source} al {target}. "
        "Devuelve solo la traducción, sin comentarios, sin comillas y sin texto adicional. "
        "No muestres tu razonamiento ni análisis (nada de 'Thinking Process'); empieza "
        f"directamente con la traducción. Si el texto de entrada ya está en {target}, "
        "devuélvelo tal cual. /no_think"
    )


def clean_output(raw):
    t = raw
    for end in ("<｜end▁of▁thinking｜>", " response", "[/think]"):
        i = t.rfind(end)
        if i >= 0:
            t = t[i + len(end):]
    t = re.sub(r"(?s)<\s*think\s*>.*?<\s*/\s*think\s*>", "", t)
    t = re.sub(r"(?i)</?think>", "", t)
    return t.strip().strip('"').strip()


def max_tokens_for(chunk_text):
    return min(MAX_OUTPUT_TOKENS, max(MIN_OUTPUT_TOKENS, len(chunk_text) // 2))


# ---- llama-server ----------------------------------------------------------
def wait_health(timeout=180):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            with urllib.request.urlopen(f"http://{HOST}:{PORT}/health", timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(1)
    return False


def build_prompt(system, user):
    """Prompt ChatML de Qwen con el bloque de razonamiento CERRADO VACÍO.

    Réplica de `build_prompt`/1b en llama_jni.cpp: el template deja `<think>`
    abierto y el modelo divaga; cerrarlo vacío fuerza la respuesta directa.
    """
    return (
        f"<|im_start|>system\n{system}<|im_end|>\n"
        f"<|im_start|>user\n{user}<|im_end|>\n"
        f"<|im_start|>assistant\n<think>\n\n</think>\n\n"
    )


def complete(system, user, max_tokens):
    """Igual que nativeGenerate: prompt crudo + muestreo greedy (top_k=1, temp=0)."""
    body = json.dumps({
        "prompt": build_prompt(system, user),
        "n_predict": max_tokens,
        "temperature": 0.0,
        "top_k": 1,
        "stream": False,
    }).encode()
    req = urllib.request.Request(
        f"http://{HOST}:{PORT}/completion",
        data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        data = json.loads(r.read())
    usage = {
        "prompt_tokens": data.get("tokens_evaluated"),
        "completion_tokens": data.get("tokens_predicted"),
    }
    return data.get("content", ""), data.get("stop_type"), usage


EN_TEXT = """Cloud computing has fundamentally changed how modern companies build and deliver software. Instead of buying and maintaining physical servers, teams rent computing power, storage and networking from large providers and pay only for what they use. This shift lowers the initial cost of launching a product and lets small companies compete with much larger ones. However, it also introduces new problems, because distributed systems are inherently harder to reason about than a single machine.

A well designed cloud application is usually split into several independent services that communicate over the network. Each service can be deployed, scaled and updated on its own, which makes the whole platform more resilient. When one component becomes slow, the others keep working, and engineers can fix the problem without taking the entire system offline. The trade off is complexity: every network call can fail, every message can arrive twice, and every clock can disagree with its neighbours. Good engineering teams therefore invest heavily in monitoring, logging and automated testing, because they know that failures are not rare exceptions but normal events that must be expected and handled gracefully.

Data is the other half of the story. Companies now collect enormous amounts of information about their users, their machines and their supply chains, and they use machine learning models to turn that raw material into useful predictions. A recommendation engine, a fraud detector and a translation service may all be trained on the same kinds of records, yet each one needs careful attention to fairness, privacy and accuracy. Governments have started to write laws that describe what may be done with personal data, and these rules differ from country to country, so global products must be flexible enough to respect local expectations while still offering a consistent experience.

Finally, the people who build these systems matter more than any tool. Technology changes quickly, but the habits of clear communication, honest documentation and patient debugging remain valuable for decades. The best teams are not the ones that never make mistakes, but the ones that learn from them, share what they discover and improve their process a little every single day. That quiet, steady progress is what turns an ambitious idea into a reliable product that millions of people can depend on."""


def main():
    if not os.path.isfile(MODEL):
        print(f"ERROR: no existe el modelo {MODEL}")
        return 2
    text = EN_TEXT.strip()
    print(f"Texto EN: {len(text)} caracteres, {len(text.split())} palabras, "
          f"{len([p for p in PARAGRAPH_SPLIT.split(text) if p.strip()])} párrafos")
    chunks = chunk(text)
    print(f"Chunker: {len(chunks)} trozos -> {[len(c) for c in chunks]}")
    assert all(len(c) <= MAX_CHARS_PER_CHUNK for c in chunks)

    log = open("/tmp/llama-server-validate.log", "w")
    proc = subprocess.Popen(
        ["llama-server", "-m", MODEL, "-c", str(N_CTX), "-t", "4",
         "--host", HOST, "--port", str(PORT), "--no-webui", "--log-disable"],
        stdout=log, stderr=subprocess.STDOUT)
    try:
        if not wait_health():
            print("ERROR: llama-server no arrancó")
            return 3
        sysmsg = system_prompt("es", "en")

        print("\n===== VIEJO: 1 petición, max_tokens=256 =====")
        old_out, old_finish, old_usage = complete(sysmsg, text, OLD_MAX_TOKENS)
        old_clean = clean_output(old_out)
        print(f"finish_reason={old_finish} usage={old_usage}")
        print(f"Salida limpia: {len(old_clean)} caracteres")
        print(old_clean[:400] + ("…" if len(old_clean) > 400 else ""))

        print("\n===== NUEVO: troceo + max_tokens dinámico =====")
        parts = []
        total_prompt_tokens = 0
        for i, ch in enumerate(chunks, 1):
            mt = max_tokens_for(ch)
            out, finish, usage = complete(sysmsg, ch, mt)
            total_prompt_tokens += usage.get("prompt_tokens", 0)
            clean = clean_output(out)
            parts.append(clean)
            print(f"  trozo {i}/{len(chunks)}: {len(ch)} chars -> max_tokens={mt} "
                  f"(finish={finish}, out_tokens={usage.get('completion_tokens')}, "
                  f"prompt_tokens={usage.get('prompt_tokens')})")
        full = "\n".join(p for p in parts if p.strip())
        print(f"\nTraducción completa: {len(full)} caracteres, "
              f"{len(full.split())} palabras, prompt_tokens acumulados={total_prompt_tokens}")
        print("\n----- TRADUCCIÓN RECOMPUESTA -----")
        print(full)

        print("\n===== VEREDICTO =====")
        old_ok = old_finish == "eos" and len(old_clean) > 0
        new_ok = len(full) > len(old_clean) * 1.5
        print(f"VIEJO truncado: {not old_ok} (finish={old_finish})")
        print(f"NUEVO completo: {new_ok} ({len(full)} chars vs {len(old_clean)} chars)")
        return 0 if new_ok else 1
    finally:
        try:
            proc.send_signal(signal.SIGINT)
            proc.wait(timeout=15)
        except Exception:
            proc.kill()
        log.close()


if __name__ == "__main__":
    sys.exit(main())
