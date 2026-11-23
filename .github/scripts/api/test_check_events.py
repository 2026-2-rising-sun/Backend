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


class RefundContractDecisionTest(unittest.TestCase):
    """#384 결정: paymentId는 항상 필수, 사유는 3개만, UNKNOWN 결과 없음."""

    def setUp(self):
        root = Path(__file__).resolve().parents[3] / "contracts/events"
        self.request_path = root / "commerce-refund-requests.yaml"
        self.request_doc = load_contract(self.request_path)
        self.result_path = root / "payment-refund-results.yaml"
        self.result_doc = load_contract(self.result_path)

    def example(self, path, document, message, name):
        found = [e for e in document["components"]["messages"][message]["examples"] if e["name"] == name]
        return copy.deepcopy(found[0]["payload"])

    def request(self, name):
        return self.example(self.request_path, self.request_doc, "RefundRequested", name)

    def result(self, name):
        return self.example(self.result_path, self.result_doc, "RefundResultRecorded", name)

    def assertRequestRejected(self, payload):
        with self.assertRaises(Exception):
            validate_payload(self.request_path, self.request_doc, "RefundRequested", payload)

    def assertResultRejected(self, payload):
        with self.assertRaises(Exception):
            validate_payload(self.result_path, self.result_doc, "RefundResultRecorded", payload)

    def test_payment_id_is_required_for_system_compensation(self):
        payload = self.request("승인전시스템보상")
        validate_payload(self.request_path, self.request_doc, "RefundRequested", payload)
        del payload["payload"]["paymentId"]
        self.assertRequestRejected(payload)

    def test_only_three_request_reasons_are_allowed(self):
        for reason in ("ORDER_EXPIRED", "ORDER_CANCELLED", "APPROVAL_RESPONSE_LOST"):
            payload = self.request("승인전시스템보상")
            payload["payload"]["reason"] = reason
            self.assertRequestRejected(payload)
        payload = self.request("사용자부분수량")
        payload["payload"]["reason"] = "APPROVAL_UNKNOWN"
        self.assertRequestRejected(payload)

    def test_result_payment_id_is_always_present(self):
        payload = self.result("보상실패뒤미승인종료")
        validate_payload(self.result_path, self.result_doc, "RefundResultRecorded", payload)
        del payload["payload"]["paymentId"]
        self.assertResultRejected(payload)
        payload = self.result("결제없음실패")
        del payload["payload"]["paymentId"]
        self.assertResultRejected(payload)

    def test_unknown_result_is_not_published(self):
        payload = self.result("사용자환불성공")
        payload["payload"]["status"] = "UNKNOWN"
        payload["payload"]["nextActionAt"] = "2026-11-21T01:10:00Z"
        self.assertResultRejected(payload)

    def test_no_charge_uses_no_charge_failure_code(self):
        payload = self.result("보상실패뒤미승인종료")
        payload["payload"]["failureCode"] = "PAYMENT_NOT_FOUND"
        self.assertResultRejected(payload)
