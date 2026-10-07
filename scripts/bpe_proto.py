#!/usr/bin/env python3
"""Prototipo/validación del BPE SentencePiece de NLLB (referencia para Kotlin)."""
import json
import sys

from tokenizers import Tokenizer

j = json.load(open(sys.argv[1] if len(sys.argv) > 1 else "nllb-onnx/tokenizer.json"))
vocab = j["model"]["vocab"]
merges = j["model"]["merges"]
id2tok = {i: t for t, i in vocab.items()}
char2id = {}
for t, i in vocab.items():
    if len(t) == 1:
        char2id.setdefault(t, i)
mr = {}
for r, m in enumerate(merges):
    a, b = m.split(" ")
    mr[(a, b)] = (r, a + b)


def bpe_ids(text, add_prefix_space=True):
    s = text.replace(" ", "\u2581")
    if add_prefix_space and not s.startswith("\u2581"):
        s = "\u2581" + s
    syms = []
    for ch in s:
        if ch not in char2id:
            return ("UNK", ch)
        syms.append(char2id[ch])
    while True:
        best = None
        for i in range(len(syms) - 1):
            k = (id2tok[syms[i]], id2tok[syms[i + 1]])
            if k in mr:
                r = mr[k][0]
                if best is None or r < best:
                    best = r
        if best is None:
            break
        for i in range(len(syms) - 1):
            k = (id2tok[syms[i]], id2tok[syms[i + 1]])
            if k in mr and mr[k][0] == best:
                syms[i:i + 2] = [vocab[mr[k][1]]]
                break
    return syms


def main():
    t = Tokenizer.from_file(sys.argv[1] if len(sys.argv) > 1 else "nllb-onnx/tokenizer.json")
    texts = [
        "Hello, how are you today? I would like a coffee, please.",
        "Buenos días, ¿dónde está la estación de tren más cercana?",
        "Der Zug nach Berlin fährt jeden Morgen um acht Uhr ab.",
        "Minä pidän suomalaisesta saunasta ja järvistä.",
        "আমি বাংলা ভাষা শিখছি",
        "The northern lights, also known as the aurora borealis, are a natural phenomenon.",
        "Buongiorno, vorrei un caffè e un cornetto, per favore.",
        "Доброе утро, где ближайшая станция метро?",
        "おはようございます、今日はいい天気ですね。",
        "مرحبا، أين أقرب محطة قطار؟",
    ]
    texts2 = list(texts)
    ok = 0
    for x in texts2:
        ref = t.encode(x, add_special_tokens=False).ids
        mine = bpe_ids(x)
        same = mine == ref
        ok += same
        print(("OK  " if same else "DIFF"), repr(x[:44]))
        if not same:
            print("   ref=", ref)
            print("   got=", mine)
    print(f"{ok}/{len(texts2)}")


if __name__ == "__main__":
    main()
