package com.lucca.ko.domain

/** Rough keyword-based category guess for a newly created pantry / shopping item. */
object CategoryGuesser {

    private val rules: List<Pair<String, List<String>>> = listOf(
        "Spices & Herbs" to listOf(
            "salt", "pepper", "paprika", "cumin", "cinnamon", "nutmeg", "oregano", "basil",
            "thyme", "rosemary", "coriander", "turmeric", "chilli", "chili", "curry", "spice",
            "bay leaf", "cardamom", "clove", "ginger", "parsley", "dill", "sage", "mint",
        ),
        "Produce" to listOf(
            "onion", "garlic", "tomato", "potato", "carrot", "pepper", "lettuce", "spinach",
            "cucumber", "lemon", "lime", "apple", "banana", "broccoli", "mushroom", "celery",
            "cabbage", "courgette", "zucchini", "aubergine", "eggplant", "avocado", "lime",
        ),
        "Dairy & Eggs" to listOf(
            "milk", "butter", "cheese", "cream", "yogurt", "yoghurt", "egg", "parmesan",
            "mozzarella", "feta", "cheddar",
        ),
        "Meat & Fish" to listOf(
            "chicken", "beef", "pork", "lamb", "bacon", "sausage", "mince", "fish", "salmon",
            "tuna", "prawn", "shrimp", "turkey", "ham", "steak",
        ),
        "Grains & Pasta" to listOf(
            "rice", "pasta", "spaghetti", "noodle", "flour", "bread", "oat", "couscous",
            "quinoa", "macaroni", "penne", "tortilla",
        ),
        "Canned & Jars" to listOf("canned", "tinned", "can of", "chickpea", "bean", "lentil"),
        "Condiments & Oils" to listOf(
            "oil", "vinegar", "soy sauce", "ketchup", "mustard", "mayonnaise", "honey",
            "stock", "broth", "sauce", "paste",
        ),
        "Baking" to listOf("sugar", "baking", "yeast", "vanilla", "cocoa", "chocolate"),
        "Frozen" to listOf("frozen"),
        "Drinks" to listOf("juice", "wine", "beer", "water", "coffee", "tea"),
    )

    fun guess(name: String): String {
        val n = name.lowercase()
        for ((category, keywords) in rules) {
            if (keywords.any { n.contains(it) }) return category
        }
        return "Other"
    }
}
