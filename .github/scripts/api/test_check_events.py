import copy
import tempfile
import unittest
from pathlib import Path

import yaml

from check_events import load_contract, validate_payload


class EventContractTest(unittest.TestCase):
    def setUp(self):
        self.path = Path(__file__).resolve().parents[3] / "contracts/events/commerce-sales-info.yaml"
        self.document = load_contract(self.path)
        self.payload = copy.deepcopy(self.document["components"]["messages"]["PriceChanged"]["examples"][0]["payload"])

    def test_required_metadata_and_numeric_constraints_are_checked(self):
        validate_payload(self.path, self.document, "PriceChanged", self.payload)
        for field in ("source", "aggregateId", "eventId", "occurredAt"):
            invalid = copy.deepcopy(self.payload)
            del invalid[field]
            with self.assertRaises(Exception):
                validate_payload(self.path, self.document, "PriceChanged", invalid)
        self.payload["payload"]["price"] = 0
        with self.assertRaises(Exception):
            validate_payload(self.path, self.document, "PriceChanged", self.payload)

    def test_aggregate_must_match_business_identifier(self):
        self.payload["aggregateId"] = "9999"
        with self.assertRaisesRegex(ValueError, "aggregateId"):
            validate_payload(self.path, self.document, "PriceChanged", self.payload)

    def test_missing_reference_is_rejected(self):
        self.document["components"]["messages"]["PriceChanged"]["payload"] = {"$ref": "#/components/schemas/Missing"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.yaml"
            path.write_text(yaml.safe_dump(self.document))
            with self.assertRaisesRegex(ValueError, "참조 대상"):
                load_contract(path)
