import csv
from decimal import Decimal
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from generate import archi, build, generate, package
from samples import write_samples


class DemoTest(unittest.TestCase):
    def test_graph_and_federated_references_are_closed_and_distinct(self):
        scenario, elements, relationships = build()
        all_ids = [e["id"] for e in elements + relationships]
        self.assertEqual(len(all_ids),len(set(all_ids)))
        element_ids = {e["id"] for e in elements}
        self.assertTrue(all(r["sourceId"] in element_ids and r["targetId"] in element_ids for r in relationships))
        canonical_ids = [item["canonicalId"] for key in ("applications","infrastructure","logicalData","datasets","products","functions") for item in scenario[key]]
        self.assertEqual(len(canonical_ids),len(set(canonical_ids)))
        datasets = {d["id"]:d for d in scenario["datasets"]}
        covered = [d for p in scenario["products"] for d in p["datasets"]]
        self.assertEqual(set(datasets),set(covered))
        self.assertEqual(len(covered),len(set(covered)),"each sample has one product owner")
        apps = {a["id"] for a in scenario["applications"]}
        self.assertTrue(all(set(d["applications"]) <= apps and set(d["upstreams"]) <= datasets.keys() for d in datasets.values()))
        model = package(elements,relationships)
        self.assertFalse(model["snapshot"]["views"])
        ops = model["opBatches"][0]["ops"]
        self.assertEqual(list(range(1,len(ops)+1)),[o["causal"]["lamport"] for o in ops])

    def test_artifacts_are_reproducible_and_archi_references_resolve(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            generate(Path(a)); generate(Path(b))
            self.assertEqual((Path(a)/"sha256.json").read_bytes(),(Path(b)/"sha256.json").read_bytes())
            tree = ET.parse(Path(a)/"order-management.archimate")
            ids = {e.attrib["id"] for e in tree.iter() if "id" in e.attrib}
            for element in tree.iter():
                for ref in ("source","target"):
                    if ref in element.attrib: self.assertIn(element.attrib[ref],ids)
            self.assertFalse(tree.findall(".//element[@{http://www.w3.org/2001/XMLSchema-instance}type='archimate:ArchimateDiagramModel']"))

    def test_ready_to_open_archi_files_match_the_semantic_fixture(self):
        _,elements,relationships = build()
        models = Path(__file__).resolve().parent / "models"
        self.assertEqual(package(elements,relationships),
                         json.loads((models/"archimesh-model.json").read_text()),
                         "ready-to-import server package must match the fixture")
        self.assertEqual(archi(elements,relationships),(models/"order-management.archimate").read_bytes().rstrip(b"\n"))
        root = ET.parse(models/"order-management-synthesized.archimate").getroot()
        type_key = "{http://www.w3.org/2001/XMLSchema-instance}type"
        semantic = {e.attrib["id"] for e in root.iter("element") if e.attrib.get(type_key) != "archimate:ArchimateDiagramModel"}
        self.assertEqual({e["id"] for e in elements + relationships},semantic)
        objects = [e for e in root.iter() if e.attrib.get(type_key) == "archimate:DiagramObject"]
        connections = [e for e in root.iter() if e.attrib.get(type_key) == "archimate:Connection"]
        self.assertEqual(16,len(objects))
        self.assertEqual(30,len(connections))

    def test_sample_keys_prices_invoices_and_sales_reconcile(self):
        with tempfile.TemporaryDirectory() as temp:
            tables = write_samples(Path(temp))
            orders = {o[0]:o for o in tables["orders"]}
            customers = {c[0] for c in tables["customers"]}
            prices = {p[0]:Decimal(p[2]) for p in tables["products"]}
            totals = {order:Decimal(0) for order in orders}
            for order,line,sku,quantity,price in tables["order-lines"]:
                self.assertIn(order,orders)
                self.assertIn(orders[order][1],customers)
                self.assertEqual(prices[sku],Decimal(price))
                totals[order] += quantity * Decimal(price)
            for invoice,order,amount,status in tables["invoices"]:
                self.assertEqual(totals[order],Decimal(amount))
            self.assertEqual(sum(totals.values()),sum(Decimal(row[-1]) for row in tables["sales-fact"]))
            scenario,_,_ = build()
            for dataset in scenario["datasets"]:
                with (Path(temp)/"data"/(dataset["id"] + ".csv")).open() as stream:
                    self.assertEqual([f[0] for f in dataset["fields"]],next(csv.reader(stream)))


if __name__ == "__main__": unittest.main()
