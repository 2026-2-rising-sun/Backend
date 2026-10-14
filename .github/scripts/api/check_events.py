"""이벤트 YAML의 참조·스키마·예시와 서비스 테스트가 직렬화한 표본을 검사한다."""
import argparse
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from check_structure import UniqueLoader, validate_references


def load_contract(path):
    document = yaml.load(path.read_text(), Loader=UniqueLoader)
    if not isinstance(document, dict) or document.get("asyncapi") != "3.1.0":
        raise ValueError(str(path) + ": AsyncAPI 3.1.0 문서가 필요합니다.")
    validate_references(path, document)
    for schema in document.get("components", {}).get("schemas", {}).values():
        Draft202012Validator.check_schema(schema)
    return document


def validate_payload(path, document, message, payload):
    messages = document.get("components", {}).get("messages", {})
    if message not in messages:
        raise ValueError(str(path) + ": 정의되지 않은 메시지 " + message)
    registry = Registry().with_resource(
        path.resolve().as_uri(), Resource.from_contents(document, default_specification=DRAFT202012)
    )
    schema = {"$id": path.resolve().as_uri(),
              "$ref": "#/components/messages/" + message + "/payload", **document}
    Draft202012Validator(schema, registry=registry, format_checker=FormatChecker()).validate(payload)
    if payload.get("eventType") != message:
        raise ValueError("eventType과 메시지 이름이 일치하지 않습니다.")
    data = payload.get("payload", {})
    aggregate = data.get("salesId", data.get("broadcastId", data.get("couponId")))
    if aggregate is not None and payload.get("aggregateId") != str(aggregate):
        raise ValueError("aggregateId와 payload의 식별자가 일치하지 않습니다.")


def check(root, samples=False):
    contracts, example_count, sample_count = {}, 0, 0
    for path in sorted((root / "contracts/events").glob("*.yaml")):
        document = load_contract(path)
        contracts[path.name] = (path, document)
        messages = document.get("components", {}).get("messages", {})
        if not messages:
            raise ValueError(str(path) + ": 메시지 정의가 없습니다.")
        for name, message in messages.items():
            if not message.get("examples"):
                raise ValueError(str(path) + ": 메시지 예시가 없습니다: " + name)
            for example in message["examples"]:
                validate_payload(path, document, name, example["payload"])
                example_count += 1
    if samples:
        for sample in sorted(root.glob("services/*/build/contract-samples/*.json")):
            record = json.loads(sample.read_text())
            path, document = contracts[record["contract"]]
            validate_payload(path, document, record["message"], record["payload"])
            sample_count += 1
        if not sample_count:
            raise ValueError("서비스 테스트가 생성한 이벤트 직렬화 표본이 없습니다.")
    return len(contracts), example_count, sample_count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--samples", action="store_true")
    args = parser.parse_args()
    counts = check(args.root, args.samples)
    print("이벤트 계약 %d개·예시 %d개·직렬화 표본 %d개 검사 통과. 실제 전달은 검사하지 않습니다." % counts)


if __name__ == "__main__":
    main()
