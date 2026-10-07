#!/usr/bin/env python3
"""
NLLB-200-distilled-600M (Xenova ONNX int8) — inference seq2seq pura con onnxruntime.

Referencia de FASE 1 (Mac) y modelo de la futura implementación Kotlin de FASE 2.

  encoder_model_quantized.onnx        input_ids, attention_mask -> last_hidden_state
  decoder_model_merged_quantized.onnx input_ids, encoder_hidden_states,
                                      encoder_attention_mask, past_key_values.*,
                                      use_cache_branch -> logits + present.*

Convención de la pareja decode/encode de NLLB (M2M100):
  - fuente:  [ <src_lang>, ...tokens..., </s> ]
  - decoder: paso 0 -> [ <tgt_lang>, </s> ]  (decoder_start_token_id = 2 = </s>)
             pasos siguientes -> [ token_anterior ] con past_key_values

Todo es ONNX puro (sin transformers/torch), tal como se portará a Android.
"""
from __future__ import annotations

import json
import os
import sys
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer, models, pre_tokenizers, decoders, processors, normalizers


# ---------------------------------------------------------------------------
# Idiomas NLLB
# ---------------------------------------------------------------------------

# La app usa ISO-639-1; NLLB usa códigos <iso639-3>_<script>.
NLLB_LANG: Dict[str, str] = {
    "es": "spa_Latn", "en": "eng_Latn", "fr": "fra_Latn", "de": "deu_Latn",
    "it": "ita_Latn", "pt": "por_Latn", "ru": "rus_Cyrl", "zh": "zho_Hans",
    "ja": "jpn_Jpan", "ko": "kor_Hang", "ar": "arb_Arab", "hi": "hin_Deva",
    "tr": "tur_Latn", "nl": "nld_Latn", "pl": "pol_Latn", "uk": "ukr_Cyrl",
    "ro": "ron_Latn", "bg": "bul_Cyrl", "hu": "hun_Latn", "sv": "swe_Latn",
    "da": "dan_Latn", "fi": "fin_Latn", "no": "nob_Latn", "cs": "ces_Latn",
    "el": "ell_Grek", "he": "heb_Hebr", "fa": "pes_Arab", "id": "ind_Latn",
    "vi": "vie_Latn", "th": "tha_Thai", "bn": "ben_Beng", "ca": "cat_Latn",
    # extras frecuentes (auto-detección / idiomas NLLB sin ISO-1 obvio)
    "sr": "srp_Cyrl", "hr": "hrv_Latn", "sk": "slk_Latn", "sl": "slv_Latn",
    "lt": "lit_Latn", "lv": "lvs_Latn", "et": "est_Latn", "ms": "zsm_Latn",
    "tl": "tgl_Latn", "sw": "swh_Latn", "ur": "urd_Arab", "ta": "tam_Taml",
    "te": "tel_Telu", "ml": "mal_Mlym", "gu": "guj_Gujr", "kn": "kan_Knda",
    "mr": "mar_Deva", "ne": "npi_Deva", "si": "sin_Sinh", "km": "khm_Khmr",
    "my": "mya_Mymr", "ka": "kat_Geor", "hy": "hye_Armn", "az": "azj_Latn",
    "kk": "kaz_Cyrl", "uz": "uzn_Latn", "af": "afr_Latn", "is": "isl_Latn",
    "ga": "gle_Latn", "cy": "cym_Latn", "eu": "eus_Latn", "gl": "glg_Latn",
}

# Inversa: código NLLB -> ISO-639-1 (para la auto-detección / mapeo en la app).
ISO_FROM_NLLB: Dict[str, str] = {v: k for k, v in NLLB_LANG.items()}


def nllb_code(iso: str) -> str:
    """ISO-639-1 -> código NLLB. Acepta ya un código NLLB ('spa_Latn')."""
    if "_" in iso:
        return iso
    if iso not in NLLB_LANG:
        raise KeyError(f"idioma sin mapeo NLLB: {iso}")
    return NLLB_LANG[iso]


# ---------------------------------------------------------------------------
# Tokenizador
# ---------------------------------------------------------------------------

class NllbTokenizer:
    """Envoltorio fino sobre `tokenizers` con el prefijo de idioma correcto.

    El tokenizer.json de Xenova fija `eng_Latn` en su TemplateProcessing, así que
    NO usamos `encode()` con especiales: montamos a mano
    `[<src_lang>] + tokens(texto) + [</s>]` (exactamente lo que hará Kotlin).
    """

    def __init__(self, tokenizer_json: str):
        self.tok = Tokenizer.from_file(tokenizer_json)
        # Quitamos el post-procesador (añade eng_Latn + </s>); lo hacemos a mano.
        self.tok.post_processor = processors.TemplateProcessing(
            single="$A", special_tokens=[],
        )

    def lang_id(self, code: str) -> int:
        tid = self.tok.token_to_id(nllb_code(code) if "_" not in code else code)
        if tid is None:
            raise KeyError(f"NLLB no conoce el token de idioma {code}")
        return tid

    def encode_source(self, text: str, src_iso: str) -> List[int]:
        ids = self.tok.encode(text, add_special_tokens=False).ids
        return [self.lang_id(src_iso)] + ids + [self.EOS]

    def decode(self, ids: Sequence[int]) -> str:
        return self.tok.decode(list(ids), skip_special_tokens=True).strip()

    EOS = 2
    PAD = 1
    DECODER_START = 2


# ---------------------------------------------------------------------------
# Motor ONNX
# ---------------------------------------------------------------------------

@dataclass
class Beam:
    ids: List[int] = field(default_factory=list)      # tokens generados (sin el prefijo)
    logprob: float = 0.0
    done: bool = False
    src: int = 0                                     # índice origen (reordenar KV)


class NllbOnnx:
    def __init__(self, model_dir: str, threads: int = 0, verbose: bool = True):
        self.dir = model_dir
        self.tokenizer = NllbTokenizer(os.path.join(model_dir, "tokenizer.json"))
        self.cfg = json.load(open(os.path.join(model_dir, "config.json")))
        self.num_layers = int(self.cfg["decoder_layers"])
        self.heads = int(self.cfg["decoder_attention_heads"])
        self.head_dim = int(self.cfg["d_model"]) // self.heads
        self.eos = int(self.cfg["eos_token_id"])
        self.pad = int(self.cfg["pad_token_id"])
        self.decoder_start = int(self.cfg["decoder_start_token_id"])
        self.verbose = verbose

        so = ort.SessionOptions()
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        if threads > 0:
            so.intra_op_num_threads = threads
        so.log_severity_level = 3

        t0 = time.time()
        self.enc = ort.InferenceSession(
            os.path.join(model_dir, "onnx", "encoder_model_quantized.onnx"),
            so, providers=["CPUExecutionProvider"])
        self.dec = ort.InferenceSession(
            os.path.join(model_dir, "onnx", "decoder_model_merged_quantized.onnx"),
            so, providers=["CPUExecutionProvider"])
        # Nombres de las past_key_values (los mismos en entrada y salida, salvo prefijo).
        self.past_inputs = [i.name for i in self.dec.get_inputs()
                            if i.name.startswith("past_key_values.")]
        self.present_outputs = [o.name for o in self.dec.get_outputs()
                                if o.name.startswith("present.")]
        if verbose:
            print(f"[nllb] modelos cargados en {time.time()-t0:.1f}s "
                  f"({len(self.past_inputs)} past KV, head_dim={self.head_dim})", flush=True)

    # -- encoder -----------------------------------------------------------
    def encode(self, text: str, src_iso: str) -> Tuple[np.ndarray, np.ndarray]:
        ids = self.tokenizer.encode_source(text, src_iso)
        input_ids = np.array([ids], dtype=np.int64)
        mask = np.ones_like(input_ids)
        hs = self.enc.run(None, {"input_ids": input_ids, "attention_mask": mask})[0]
        return hs, mask

    # -- un paso de decoder (batch = nº de beams) --------------------------
    def _decoder_step(
        self,
        input_ids: np.ndarray,            # [B, T]
        enc_hidden: np.ndarray,           # [B, S, 1024]
        enc_mask: np.ndarray,             # [B, S]
        past: Dict[str, np.ndarray],
        first: bool,
    ):
        feeds: Dict[str, np.ndarray] = {
            "input_ids": input_ids.astype(np.int64),
            "encoder_hidden_states": enc_hidden.astype(np.float32),
            "encoder_attention_mask": enc_mask.astype(np.int64),
            "use_cache_branch": np.array([not first], dtype=bool),
        }
        b = input_ids.shape[0]
        for name in self.past_inputs:
            if name in past:
                feeds[name] = past[name]
            else:
                # past vacío (longitud 0) -> el grafo lo ignora con use_cache_branch=False
                feeds[name] = np.zeros((b, self.heads, 0, self.head_dim), dtype=np.float32)
        outs = self.dec.run(self.present_outputs + ["logits"], feeds)
        present = outs[:-1]
        logits = outs[-1]
        new_past: Dict[str, np.ndarray] = {}
        for pname, tensor in zip(self.past_inputs, present):
            # present.N.* -> past_key_values.N.*
            # OJO: el modelo *merged* de Optimum devuelve los present.encoder.*
            # vacíos (batch 0) en la rama con caché. La KV de cross-attention no
            # cambia entre pasos, así que conservamos la del primer paso.
            if "encoder" in pname and pname in past and past[pname].shape[2] > 0:
                new_past[pname] = past[pname]
            elif tensor.ndim == 4 and tensor.shape[0] != b and pname in past:
                new_past[pname] = past[pname]
            else:
                new_past[pname] = tensor
        return logits, new_past

    # -- greedy ------------------------------------------------------------
    def greedy(self, text: str, src_iso: str, tgt_iso: str,
               max_new_tokens: int = 256, min_new_tokens: int = 0) -> str:
        hs, mask = self.encode(text, src_iso)
        tgt_id = self.tokenizer.lang_id(tgt_iso)
        # Convención M2M100/NLLB: el decoder arranca con [</s>, <tgt_lang>]
        # (equivale al decoder_start_token_id + forced_bos_token_id de HF).
        ids = np.array([[self.decoder_start, tgt_id]], dtype=np.int64)
        past: Dict[str, np.ndarray] = {}
        generated: List[int] = []
        first = True
        for step in range(max_new_tokens):
            logits, past = self._decoder_step(ids, hs, mask, past, first)
            first = False
            nxt = int(np.argmax(logits[0, -1, :]))
            if nxt == self.eos and len(generated) >= min_new_tokens:
                break
            generated.append(nxt)
            ids = np.array([[nxt]], dtype=np.int64)
        return self.tokenizer.decode(generated)

    # -- beam search -------------------------------------------------------
    def beam(self, text: str, src_iso: str, tgt_iso: str, num_beams: int = 5,
             max_new_tokens: int = 256, length_penalty: float = 1.0) -> str:
        hs1, mask1 = self.encode(text, src_iso)
        tgt_id = self.tokenizer.lang_id(tgt_iso)

        # Batch de beams: mismos estados de encoder replicados.
        hs = np.repeat(hs1, num_beams, axis=0)
        mask = np.repeat(mask1, num_beams, axis=0)

        beams = [Beam() for _ in range(num_beams)]
        # convención M2M100/NLLB: [</s>, <tgt_lang>]
        ids = np.tile(np.array([[self.decoder_start, tgt_id]], dtype=np.int64),
                      (num_beams, 1))
        past: Dict[str, np.ndarray] = {}
        first = True

        for _step in range(max_new_tokens):
            logits, present = self._decoder_step(ids, hs, mask, past, first)
            first = False
            cur = logits[:, -1, :].astype(np.float64)                    # [B, V]
            total = _log_softmax(cur) + \
                np.array([b.logprob for b in beams])[:, None]

            vocab = total.shape[1]
            flat = total.reshape(-1)
            k = min(num_beams * 2, flat.size)
            top = np.argpartition(-flat, k - 1)[:k]
            top = top[np.argsort(-flat[top])]

            new_beams: List[Beam] = []
            for idx in top:
                src_beam = int(idx // vocab)
                tok = int(idx % vocab)
                prev = beams[src_beam]
                cand = Beam(ids=list(prev.ids), logprob=float(flat[idx]),
                            done=prev.done, src=src_beam)
                if not prev.done:
                    if tok == self.eos:
                        cand.done = True
                    else:
                        cand.ids.append(tok)
                new_beams.append(cand)
                if len(new_beams) >= num_beams:
                    break

            beams = new_beams
            # Reordena las present KV según el beam de origen de cada candidato.
            order = [b.src for b in beams]
            past = {name: np.concatenate([present[name][i:i + 1] for i in order], axis=0)
                    for name in self.past_inputs}
            ids = np.array([[b.ids[-1] if b.ids else self.eos] for b in beams],
                           dtype=np.int64)

            if all(b.done for b in beams):
                break

        def score(b: Beam) -> float:
            return b.logprob / (max(1, len(b.ids)) ** length_penalty)

        best = max(beams, key=score)
        return self.tokenizer.decode(best.ids)


# ---------------------------------------------------------------------------
# Troceado de textos largos
# ---------------------------------------------------------------------------

def _log_softmax(x: np.ndarray) -> np.ndarray:
    """log_softmax estable por filas (numpy 2.x ya no trae log_softmax)."""
    m = np.max(x, axis=-1, keepdims=True)
    e = np.exp(x - m)
    return (x - m) - np.log(np.sum(e, axis=-1, keepdims=True))


def chunk_text(text: str, max_chars: int = 400) -> List[str]:
    """Una FRASE por trozo (partiendo por párrafo y luego por frase).

    NLLB-600M int8, ante una entrada con varias frases, tiende a emitir </s>
    tras la primera y descartar el resto (comprobado en el Mac con las rutas
    CON y SIN caché, así que no es un bug de nuestra gestión de past_key_values).
    Traducir frase a frase evita perder contenido; solo si una frase supera
    `max_chars` se parte además por palabras.
    """
    import re
    text = text.strip()
    if not text:
        return []
    units: List[str] = []
    for para in text.split("\n"):
        para = para.strip()
        if not para:
            continue
        for sent in re.split(r"(?<=[.!?…:\u3002\uff01\uff1f])\s+", para):
            sent = sent.strip()
            if not sent:
                continue
            if len(sent) <= max_chars:
                units.append(sent)
            else:
                cur = ""
                for word in sent.split(" "):
                    if cur and len(cur) + 1 + len(word) > max_chars:
                        units.append(cur)
                        cur = word
                    else:
                        cur = f"{cur} {word}".strip()
                if cur:
                    units.append(cur)
    return units or [text]


def translate(self: NllbOnnx, text: str, src: str, tgt: str, *,
              num_beams: int = 4, max_new_tokens: int = 256,
              max_chars: int = 400) -> str:
    """Traduce texto (posiblemente largo) troceando y recomponiendo."""
    parts = []
    for piece in chunk_text(text, max_chars):
        if num_beams > 1:
            parts.append(self.beam(piece, src, tgt, num_beams=num_beams,
                                  max_new_tokens=max_new_tokens))
        else:
            parts.append(self.greedy(piece, src, tgt, max_new_tokens=max_new_tokens))
    return "\n".join(p for p in parts if p)


NllbOnnx.translate = translate  # type: ignore[attr-defined]


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="nllb-onnx")
    ap.add_argument("--src", required=True)
    ap.add_argument("--tgt", required=True)
    ap.add_argument("--text", default=None)
    ap.add_argument("--beams", type=int, default=4)
    ap.add_argument("--threads", type=int, default=0)
    a = ap.parse_args()

    text = a.text
    if text is None:
        text = sys.stdin.read()

    eng = NllbOnnx(a.model, threads=a.threads)
    t0 = time.time()
    out = eng.translate(text, a.src, a.tgt, num_beams=a.beams)
    dt = time.time() - t0
    print(out)
    print(f"\n[-- {dt:.1f}s, {a.src}->{a.tgt}, beams={a.beams}]", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
