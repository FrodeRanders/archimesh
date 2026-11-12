"""Small, consistent business records backing the synthetic catalog descriptions."""
import csv
from decimal import Decimal


def write_samples(output):
    tables = {
        "customers": [("C001","Demo Harbour Tools","SE"),("C002","Demo Forest Supply","FI"),("C003","Demo Island Hardware","DK")],
        "products": [("P100","Trade fastener pack","125.00"),("P200","Workshop tool kit","750.00"),("P300","Safety glove box","250.00")],
        "orders": [("O1001","C001","2026-01-12T09:00:00Z","INVOICED"),("O1002","C002","2026-01-13T10:00:00Z","SHIPPED"),("O1003","C003","2026-01-14T11:00:00Z","ACCEPTED")],
        "order-lines": [("O1001",1,"P100",4,"125.00"),("O1001",2,"P200",2,"750.00"),
                        ("O1002",1,"P200",1,"750.00"),("O1002",2,"P300",3,"250.00"),
                        ("O1003",1,"P100",8,"125.00"),("O1003",2,"P300",2,"250.00")],
        "inventory": [("P100","WH01",100,8),("P200","WH01",30,0),("P300","WH01",50,2)],
        "shipments": [("S1001","O1001","2026-01-12T16:00:00Z","DELIVERED"),("S1002","O1002","2026-01-13T16:00:00Z","IN_TRANSIT")],
        "invoices": [("I1001","O1001","2000.00","PAID"),("I1002","O1002","1500.00","OPEN")]
    }
    tables["order-events"] = [("E" + order[0][1:],order[0],order[2]) for order in tables["orders"]]
    orders = {o[0]:o for o in tables["orders"]}
    tables["sales-fact"] = [(orders[line[0]][2][:10],orders[line[0]][1],line[2],line[3],
                             str(Decimal(line[4]) * line[3])) for line in tables["order-lines"]]
    # The declarative schema is shared by the metadata and the sample CSVs.
    from generate import build
    fixture, _, _ = build()
    destination = output / "data"
    destination.mkdir(exist_ok=True)
    for dataset in fixture["datasets"]:
        with (destination / (dataset["id"] + ".csv")).open("w",newline="") as stream:
            writer = csv.writer(stream,lineterminator="\n")
            writer.writerow([f[0] for f in dataset["fields"]])
            writer.writerows(tables[dataset["id"]])
    return tables
