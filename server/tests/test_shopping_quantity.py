from ko_sync import store


def test_a_shopping_amount_round_trips():
    store.insert_shopping_item({"remoteId": "s1", "name": "flour", "quantity": "1 kg"})
    store.insert_shopping_item({"remoteId": "s2", "name": "milk"})
    by_name = {i["name"]: i for i in store.all_shopping_items()}
    assert by_name["flour"]["quantity"] == "1 kg"
    assert by_name["milk"]["quantity"] is None
