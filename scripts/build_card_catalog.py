"""Generate browser/server card metadata from the existing, user-supplied CSV.

No rules are inferred from prose. Executable coverage lives in card_rules.cljc.
Run this script after editing decks.csv; identifiers include edition and pack.
"""
import collections
import csv
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
def slug(value):
    # Include a digest for Unicode-only labels rather than collapsing deck names.
    text = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return text or hashlib.sha256(value.encode()).hexdigest()[:8]
def edn(value):
    if isinstance(value, bool): return "true" if value else "false"
    if value is None: return "nil"
    if isinstance(value, str): return json.dumps(value, ensure_ascii=False)
    if isinstance(value, (int, float)): return str(value)
    if isinstance(value, list): return "[" + " ".join(map(edn, value)) + "]"
    return "{" + " ".join(":" + k + " " + edn(v) for k, v in value.items()) + "}"

rows = list(csv.DictReader((ROOT / "decks.csv").open()))
seen = collections.Counter()
cards = []
for row in rows:
    identity = "-".join(map(slug, [row["Edition"], row["Base/Expansion"], row["Deck"] or "major", row["Number"], row["Name"]]))
    seen[identity] += 1
    if seen[identity] > 1:
        identity += "-" + str(seen[identity])
    cost = {}
    for amount, resource in re.findall(r"(\d+) (Wood|Clay|Reed|Stone|Food|Grain|Vegetable|Sheep|Wild boar|Cattle)\b", row["Cost"], re.I):
        cost[resource.lower().replace("wild boar", "boar")] = int(amount)
    card = {"id": identity, "edition": row["Edition"], "pack": row["Base/Expansion"],
            "deck": row["Deck"], "number": row["Number"], "type": row["Type"],
            "name": row["Name"], "min-players": int((row["Player(s)"] or "1")[0]),
            "cost-text": row["Cost"], "prerequisites": row["Prerequisites"],
            "points-text": row["VPs"], "passing?": row["Passing Left"] == "X", "text": row["Text"]}
    # Parsed costs are informational until a rule implementation opts into them.
    card["printed-cost"] = cost
    cards.append(card)
text = '(ns agricola.card-catalog "Generated from decks.csv by scripts/build_card_catalog.py. Do not infer executable support from a catalogue entry.")\n\n'
text += '(def cards\n  [\n' + '\n'.join('   ' + edn(c) for c in cards) + '])\n'
text += '(def by-id (into {} (map (juxt :id identity) cards)))\n'
text += '(def revised-base (filterv #(and (= "Revised" (:edition %)) (= "Base (Revised)" (:pack %))) cards))\n'
(ROOT / 'src/main/cljc/agricola/card_catalog.cljc').write_text(text)
print(f'Generated {len(cards)} unique catalogue records')
