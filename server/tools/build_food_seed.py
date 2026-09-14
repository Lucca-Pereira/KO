"""Generates ``data/foods_seed.json``.

Values are per 100 g of the edible portion, rounded to the precision a food diary can actually
use, and derived from USDA FNDDS / SR Legacy (public domain). Keeping this as a script rather
than a hand-edited JSON blob means the shape stays consistent and a correction is a one-line
diff in a readable table.

``serving_grams`` is what makes "2 eggs" or "1 scoop" weighable — without it the estimator
refuses to guess rather than inventing calories.

Run: ``python tools/build_food_seed.py``
"""

from __future__ import annotations

import json
import time
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "data" / "foods_seed.json"

# name, kcal, protein, carbs, fat, fiber, serving_grams, serving_label, is_supplement
Row = tuple[str, float, float, float, float, float | None, float | None, str | None, bool]

FOODS: list[Row] = [
    # ---- Eggs and dairy ------------------------------------------------------------
    ("egg", 143, 12.6, 0.7, 9.5, 0, 50, "1 medium egg", False),
    ("egg white", 52, 10.9, 0.7, 0.2, 0, 33, "1 white", False),
    ("egg yolk", 322, 15.9, 3.6, 26.5, 0, 17, "1 yolk", False),
    ("whole milk", 61, 3.2, 4.8, 3.3, 0, 244, "1 cup", False),
    ("semi skimmed milk", 50, 3.3, 4.9, 1.8, 0, 244, "1 cup", False),
    ("skimmed milk", 34, 3.4, 5.0, 0.1, 0, 244, "1 cup", False),
    ("greek yoghurt", 59, 10.2, 3.6, 0.4, 0, 170, "1 pot", False),
    ("natural yoghurt", 61, 3.5, 4.7, 3.3, 0, 170, "1 pot", False),
    ("cheddar", 403, 24.9, 1.3, 33.1, 0, 30, "1 slice", False),
    ("mozzarella", 300, 22.2, 2.2, 22.4, 0, 28, "1 slice", False),
    ("parmesan", 392, 35.8, 3.2, 25.8, 0, 5, "1 tbsp grated", False),
    ("feta", 264, 14.2, 4.1, 21.3, 0, 28, "1 oz", False),
    ("cream cheese", 342, 5.9, 5.5, 34.2, 0, 15, "1 tbsp", False),
    ("butter", 717, 0.9, 0.1, 81.1, 0, 14, "1 tbsp", False),
    ("double cream", 340, 2.1, 2.8, 35.5, 0, 15, "1 tbsp", False),
    ("cottage cheese", 98, 11.1, 3.4, 4.3, 0, 113, "1/2 cup", False),
    # ---- Meat and fish -------------------------------------------------------------
    ("chicken breast", 165, 31.0, 0.0, 3.6, 0, 174, "1 breast", False),
    ("chicken thigh", 209, 26.0, 0.0, 10.9, 0, 111, "1 thigh", False),
    ("chicken wing", 203, 30.5, 0.0, 8.1, 0, 34, "1 wing", False),
    ("turkey breast", 135, 30.1, 0.0, 0.7, 0, 100, None, False),
    ("beef mince", 250, 26.0, 0.0, 15.0, 0, 100, None, False),
    ("lean beef mince", 176, 27.0, 0.0, 7.0, 0, 100, None, False),
    ("beef steak", 271, 25.9, 0.0, 18.0, 0, 220, "1 steak", False),
    ("pork chop", 231, 25.7, 0.0, 13.9, 0, 150, "1 chop", False),
    ("bacon", 541, 37.0, 1.4, 42.0, 0, 20, "1 rasher", False),
    ("ham", 145, 20.9, 1.5, 5.5, 0, 28, "1 slice", False),
    ("chorizo", 455, 24.1, 1.9, 38.3, 0, 30, None, False),
    ("sausage", 301, 12.3, 2.5, 26.9, 0, 60, "1 sausage", False),
    ("lamb", 294, 24.5, 0.0, 20.9, 0, 100, None, False),
    ("salmon", 208, 20.4, 0.0, 13.4, 0, 150, "1 fillet", False),
    ("tuna", 132, 28.0, 0.0, 1.3, 0, 100, None, False),
    ("canned tuna", 116, 25.5, 0.0, 0.8, 0, 145, "1 tin", False),
    ("cod", 82, 17.8, 0.0, 0.7, 0, 150, "1 fillet", False),
    ("prawn", 99, 24.0, 0.2, 0.3, 0, 100, None, False),
    ("sardine", 208, 24.6, 0.0, 11.5, 0, 90, "1 tin", False),
    # ---- Grains, bread, pasta ------------------------------------------------------
    ("white rice", 130, 2.7, 28.2, 0.3, 0.4, 158, "1 cup cooked", False),
    ("brown rice", 123, 2.7, 25.6, 1.0, 1.6, 195, "1 cup cooked", False),
    ("dry white rice", 365, 7.1, 80.0, 0.7, 1.3, 100, None, False),
    ("pasta", 158, 5.8, 30.9, 0.9, 1.8, 140, "1 cup cooked", False),
    ("dry pasta", 371, 13.0, 74.7, 1.5, 3.2, 100, None, False),
    ("wholewheat pasta", 124, 5.3, 26.5, 0.5, 3.9, 140, "1 cup cooked", False),
    ("oats", 379, 13.2, 67.7, 6.5, 10.1, 40, "1 serving", False),
    ("white bread", 265, 9.0, 49.0, 3.2, 2.7, 30, "1 slice", False),
    ("wholemeal bread", 247, 13.0, 41.0, 3.4, 7.0, 30, "1 slice", False),
    ("tortilla wrap", 306, 8.2, 51.4, 7.4, 3.0, 45, "1 wrap", False),
    ("couscous", 112, 3.8, 23.2, 0.2, 1.4, 157, "1 cup cooked", False),
    ("quinoa", 120, 4.4, 21.3, 1.9, 2.8, 185, "1 cup cooked", False),
    ("plain flour", 364, 10.3, 76.3, 1.0, 2.7, 125, "1 cup", False),
    ("breadcrumbs", 395, 13.4, 71.9, 5.3, 4.5, 30, None, False),
    ("cornflakes", 357, 7.5, 84.1, 0.4, 3.3, 30, "1 bowl", False),
    ("granola", 471, 10.0, 64.0, 20.0, 7.0, 50, "1 serving", False),
    # ---- Legumes and nuts ----------------------------------------------------------
    ("chickpea", 164, 8.9, 27.4, 2.6, 7.6, 240, "1 tin drained", False),
    ("black bean", 132, 8.9, 23.7, 0.5, 8.7, 240, "1 tin drained", False),
    ("kidney bean", 127, 8.7, 22.8, 0.5, 6.4, 240, "1 tin drained", False),
    ("lentil", 116, 9.0, 20.1, 0.4, 7.9, 198, "1 cup cooked", False),
    ("baked bean", 94, 4.9, 15.5, 0.5, 4.1, 200, "1/2 tin", False),
    ("peanut", 567, 25.8, 16.1, 49.2, 8.5, 28, "1 handful", False),
    ("peanut butter", 588, 25.1, 19.6, 50.4, 6.0, 32, "2 tbsp", False),
    ("almond", 579, 21.2, 21.6, 49.9, 12.5, 28, "1 handful", False),
    ("walnut", 654, 15.2, 13.7, 65.2, 6.7, 28, "1 handful", False),
    ("cashew", 553, 18.2, 30.2, 43.9, 3.3, 28, "1 handful", False),
    ("tofu", 76, 8.1, 1.9, 4.8, 0.3, 100, None, False),
    ("tempeh", 192, 20.3, 7.6, 10.8, 0, 100, None, False),
    # ---- Vegetables ----------------------------------------------------------------
    ("potato", 77, 2.0, 17.5, 0.1, 2.2, 173, "1 medium", False),
    ("sweet potato", 86, 1.6, 20.1, 0.1, 3.0, 130, "1 medium", False),
    ("onion", 40, 1.1, 9.3, 0.1, 1.7, 110, "1 medium", False),
    ("garlic", 149, 6.4, 33.1, 0.5, 2.1, 3, "1 clove", False),
    ("tomato", 18, 0.9, 3.9, 0.2, 1.2, 123, "1 medium", False),
    ("tinned tomato", 32, 1.6, 7.3, 0.3, 1.9, 400, "1 tin", False),
    ("carrot", 41, 0.9, 9.6, 0.2, 2.8, 61, "1 medium", False),
    ("broccoli", 34, 2.8, 6.6, 0.4, 2.6, 91, "1 cup", False),
    ("cauliflower", 25, 1.9, 5.0, 0.3, 2.0, 100, None, False),
    ("spinach", 23, 2.9, 3.6, 0.4, 2.2, 30, "1 handful", False),
    ("lettuce", 15, 1.4, 2.9, 0.2, 1.3, 36, "1 cup", False),
    ("cucumber", 15, 0.7, 3.6, 0.1, 0.5, 300, "1 whole", False),
    ("bell pepper", 31, 1.0, 6.0, 0.3, 2.1, 119, "1 medium", False),
    ("courgette", 17, 1.2, 3.1, 0.3, 1.0, 196, "1 medium", False),
    ("aubergine", 25, 1.0, 5.9, 0.2, 3.0, 250, "1 medium", False),
    ("mushroom", 22, 3.1, 3.3, 0.3, 1.0, 70, "1 cup", False),
    ("peas", 81, 5.4, 14.5, 0.4, 5.7, 100, None, False),
    ("sweetcorn", 86, 3.3, 19.0, 1.4, 2.0, 100, None, False),
    ("green bean", 31, 1.8, 7.0, 0.1, 2.7, 100, None, False),
    ("cabbage", 25, 1.3, 5.8, 0.1, 2.5, 100, None, False),
    ("leek", 61, 1.5, 14.2, 0.3, 1.8, 89, "1 leek", False),
    ("celery", 16, 0.7, 3.0, 0.2, 1.6, 40, "1 stalk", False),
    ("avocado", 160, 2.0, 8.5, 14.7, 6.7, 150, "1 whole", False),
    ("olive", 115, 0.8, 6.3, 10.7, 3.2, 15, None, False),
    # ---- Fruit ---------------------------------------------------------------------
    ("banana", 89, 1.1, 22.8, 0.3, 2.6, 118, "1 medium", False),
    ("apple", 52, 0.3, 13.8, 0.2, 2.4, 182, "1 medium", False),
    ("orange", 47, 0.9, 11.8, 0.1, 2.4, 131, "1 medium", False),
    ("strawberry", 32, 0.7, 7.7, 0.3, 2.0, 150, "1 cup", False),
    ("blueberry", 57, 0.7, 14.5, 0.3, 2.4, 148, "1 cup", False),
    ("grape", 69, 0.7, 18.1, 0.2, 0.9, 92, "1 cup", False),
    ("pineapple", 50, 0.5, 13.1, 0.1, 1.4, 165, "1 cup", False),
    ("mango", 60, 0.8, 15.0, 0.4, 1.6, 200, "1 whole", False),
    ("lemon", 29, 1.1, 9.3, 0.3, 2.8, 58, "1 whole", False),
    ("raisin", 299, 3.1, 79.2, 0.5, 3.7, 30, "1 handful", False),
    ("date", 282, 2.5, 75.0, 0.4, 8.0, 24, "1 date", False),
    # ---- Fats, sauces, sugar --------------------------------------------------------
    ("olive oil", 884, 0.0, 0.0, 100.0, 0, 14, "1 tbsp", False),
    ("sunflower oil", 884, 0.0, 0.0, 100.0, 0, 14, "1 tbsp", False),
    ("mayonnaise", 680, 1.0, 0.6, 75.0, 0, 14, "1 tbsp", False),
    ("ketchup", 101, 1.0, 25.8, 0.1, 0.3, 17, "1 tbsp", False),
    ("soy sauce", 53, 8.1, 4.9, 0.6, 0.8, 16, "1 tbsp", False),
    ("honey", 304, 0.3, 82.4, 0.0, 0.2, 21, "1 tbsp", False),
    ("sugar", 387, 0.0, 100.0, 0.0, 0, 4, "1 tsp", False),
    ("dark chocolate", 546, 4.9, 61.2, 31.3, 7.0, 25, None, False),
    ("milk chocolate", 535, 7.6, 59.4, 29.7, 3.4, 25, None, False),
    ("salt", 0, 0.0, 0.0, 0.0, 0, 6, "1 tsp", False),
    ("stock cube", 214, 12.0, 20.0, 10.0, 0, 10, "1 cube", False),
    ("coconut milk", 197, 2.0, 2.8, 21.3, 0, 400, "1 tin", False),
    ("hummus", 166, 7.9, 14.3, 9.6, 6.0, 30, "1 tbsp", False),
    # ---- Gym: supplements and sports food -------------------------------------------
    ("whey protein powder", 375, 80.0, 7.5, 5.0, 0, 30, "1 scoop", True),
    ("whey isolate powder", 370, 88.0, 2.0, 1.0, 0, 30, "1 scoop", True),
    ("casein protein powder", 360, 78.0, 8.0, 3.0, 0, 30, "1 scoop", True),
    ("vegan protein powder", 380, 75.0, 8.0, 6.0, 3.0, 30, "1 scoop", True),
    ("creatine monohydrate", 0, 0.0, 0.0, 0.0, 0, 5, "1 scoop", True),
    ("mass gainer", 380, 20.0, 65.0, 4.0, 2.0, 100, "1 scoop", True),
    ("protein bar", 350, 30.0, 35.0, 9.0, 6.0, 60, "1 bar", True),
    ("protein shake ready to drink", 42, 6.7, 2.5, 0.6, 0, 330, "1 bottle", True),
    ("bcaa powder", 0, 0.0, 0.0, 0.0, 0, 7, "1 scoop", True),
    ("electrolyte powder", 10, 0.0, 2.5, 0.0, 0, 6, "1 sachet", True),
    ("isotonic sports drink", 24, 0.0, 6.0, 0.0, 0, 500, "1 bottle", True),
    ("energy gel", 250, 0.0, 62.0, 0.0, 0, 40, "1 gel", True),
    ("maltodextrin", 380, 0.0, 95.0, 0.0, 0, 30, "1 scoop", True),
    ("omega 3 capsule", 902, 0.0, 0.0, 100.0, 0, 1, "1 capsule", True),
    ("multivitamin tablet", 0, 0.0, 0.0, 0.0, 0, 1, "1 tablet", True),
    # ---- Drinks ---------------------------------------------------------------------
    ("black coffee", 1, 0.1, 0.0, 0.0, 0, 240, "1 cup", False),
    ("tea", 1, 0.0, 0.3, 0.0, 0, 240, "1 cup", False),
    ("orange juice", 45, 0.7, 10.4, 0.2, 0.2, 248, "1 glass", False),
    ("beer", 43, 0.5, 3.6, 0.0, 0, 330, "1 bottle", False),
    ("red wine", 85, 0.1, 2.6, 0.0, 0, 150, "1 glass", False),
    ("cola", 42, 0.0, 10.6, 0.0, 0, 330, "1 can", False),
    ("diet cola", 0, 0.0, 0.0, 0.0, 0, 330, "1 can", False),
    ("almond milk", 17, 0.6, 0.6, 1.1, 0.4, 244, "1 cup", False),
    ("oat milk", 46, 0.8, 7.0, 1.5, 0.8, 244, "1 cup", False),
]


def _to_entry(row: Row) -> dict:
    name, kcal, protein, carbs, fat, fiber, grams, label, supplement = row
    return {
        "name": name,
        "source": "LOCAL",
        "serving_label": label,
        "serving_grams": grams,
        "kcal_per_100": kcal,
        "protein_per_100": protein,
        "carbs_per_100": carbs,
        "fat_per_100": fat,
        "fiber_per_100": fiber,
        "is_supplement": supplement,
    }


def main() -> None:
    foods = [_to_entry(row) for row in FOODS]

    names = [f["name"] for f in foods]
    duplicates = {n for n in names if names.count(n) > 1}
    if duplicates:
        raise SystemExit(f"Duplicate food names: {sorted(duplicates)}")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(
            {"version": 1, "generated_at": int(time.time()), "foods": foods},
            indent=1,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {len(foods)} foods to {OUT}")


if __name__ == "__main__":
    main()
