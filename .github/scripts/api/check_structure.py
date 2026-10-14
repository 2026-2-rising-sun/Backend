"""서버 실행 없이 Spring MVC 선언과 OpenAPI의 경로·HTTP 메서드를 양방향 비교한다."""
import argparse
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit

import tree_sitter_java
import yaml
from tree_sitter import Language, Parser

HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
MAPPINGS = {name.title() + "Mapping": name for name in HTTP_METHODS}
JAVA = Language(tree_sitter_java.language())


class UniqueLoader(yaml.SafeLoader):
    """중복 키가 앞선 API 선언을 조용히 덮어쓰지 못하게 한다."""


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError("OpenAPI 중복 키: " + str(key))
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def annotations(declaration):
    modifiers = next((n for n in declaration.named_children if n.type == "modifiers"), None)
    return [] if modifiers is None else [
        n for n in modifiers.named_children if n.type in {"annotation", "marker_annotation"}
    ]


def name(annotation):
    return annotation.child_by_field_name("name").text.decode().split(".")[-1]


def values(expression, enum=False):
    if expression.type == "element_value_array_initializer":
        return [item for child in expression.named_children for item in values(child, enum)]
    if enum and expression.type == "field_access":
        value = expression.child_by_field_name("field").text.decode().lower()
        if value in HTTP_METHODS:
            return [value]
    if not enum and expression.type == "string_literal":
        return [json.loads(expression.text.decode())]
    if not enum and expression.type == "binary_expression":
        left, right = expression.child_by_field_name("left"), expression.child_by_field_name("right")
        operator = expression.child_by_field_name("operator")
        if operator is not None and operator.text == b"+":
            return [a + b for a in values(left) for b in values(right)]
    raise ValueError("해석할 수 없는 매핑 표현식: " + expression.text.decode())


def mapping(annotation):
    kind = name(annotation)
    if kind not in MAPPINGS and kind != "RequestMapping":
        raise ValueError("지원하지 않는 매핑 어노테이션: " + kind)
    paths, methods = [""], [MAPPINGS[kind]] if kind in MAPPINGS else []
    arguments = annotation.child_by_field_name("arguments")
    seen_path = False
    for argument in [] if arguments is None else arguments.named_children:
        key, expression = "value", argument
        if argument.type == "element_value_pair":
            key = argument.child_by_field_name("key").text.decode()
            expression = argument.child_by_field_name("value")
        if key in {"value", "path"}:
            if seen_path:
                raise ValueError("value와 path를 동시에 지정한 매핑")
            paths, seen_path = values(expression), True
        elif key == "method":
            methods = values(expression, enum=True)
    return paths, methods


def controller_routes(source, filename):
    """Java AST를 사용하므로 주석·문자열 속 가짜 매핑을 읽지 않는다."""
    tree = Parser(JAVA).parse(source.encode())
    if tree.root_node.has_error:
        raise ValueError(str(filename) + ": Java 구문을 해석할 수 없습니다.")
    routes = {}
    pending = list(tree.root_node.named_children)
    while pending:
        node = pending.pop()
        if node.type not in {"class_declaration", "interface_declaration"}:
            pending.extend(node.named_children)
            continue
        attrs = annotations(node)
        if not any(name(a) in {"RestController", "Controller"} for a in attrs):
            pending.extend(node.named_children)
            continue
        if node.child_by_field_name("superclass") or node.child_by_field_name("interfaces"):
            raise ValueError(str(filename) + ": 상속·인터페이스 매핑은 현재 정적 검사 범위 밖입니다.")
        bases, class_methods = [""], []
        for attr in attrs:
            if name(attr).endswith("Mapping"):
                bases, class_methods = mapping(attr)
        body = node.child_by_field_name("body")
        for method in body.named_children:
            if method.type != "method_declaration":
                continue
            for attr in annotations(method):
                if not name(attr).endswith("Mapping"):
                    continue
                paths, verbs = mapping(attr)
                if class_methods:
                    verbs = [v for v in verbs if v in class_methods] if verbs else class_methods
                if not verbs:
                    raise ValueError(str(filename) + ": HTTP 메서드를 명시해야 합니다.")
                for base in bases:
                    for path in paths:
                        # 마지막 /는 별도 경로다. PathVariable 이름도 그대로 비교한다.
                        full = ("/" + base.strip("/") if base.strip("/") else "")
                        full += "/" + path.lstrip("/") if path else ""
                        full = full or "/"
                        for verb in verbs:
                            routes.setdefault((verb, full), []).append(
                                str(filename) + ":" + str(attr.start_point.row + 1)
                            )
    return routes


def validate_references(path, document):
    """로컬 $ref의 파일·JSON Pointer만 확인한다. 스키마나 실제 응답은 검증하지 않는다."""
    documents = {path.resolve(): document}
    visited = set()

    def walk(value, current):
        if isinstance(value, list):
            for item in value:
                walk(item, current)
        elif isinstance(value, dict):
            if "$ref" in value:
                reference = value["$ref"]
                if not isinstance(reference, str):
                    raise ValueError(str(current) + ": $ref는 문자열이어야 합니다.")
                uri = urlsplit(reference)
                if uri.scheme or uri.netloc or uri.query:
                    raise ValueError(str(current) + ": 로컬 문서 참조만 지원합니다: " + reference)
                target = (current.parent / unquote(uri.path)).resolve() if uri.path else current
                fragment = unquote(uri.fragment)
                if fragment and not fragment.startswith("/"):
                    raise ValueError(str(current) + ": JSON Pointer 참조가 필요합니다: " + reference)
                if target not in documents:
                    if not target.is_file():
                        raise ValueError(str(current) + ": 참조 파일이 없습니다: " + reference)
                    with target.open() as stream:
                        documents[target] = yaml.load(stream, Loader=UniqueLoader)
                node = documents[target]
                try:
                    for token in fragment.split("/")[1:]:
                        token = token.replace("~1", "/").replace("~0", "~")
                        node = node[int(token)] if isinstance(node, list) else node[token]
                except (KeyError, IndexError, ValueError, TypeError) as error:
                    raise ValueError(str(current) + ": 참조 대상이 없습니다: " + reference) from error
                key = (target, fragment)
                if key not in visited:
                    visited.add(key)
                    walk(node, target)
            for key, child in value.items():
                # 예시의 업무 데이터에 들어있는 $ref는 문서 참조가 아니다.
                if key == "examples" and isinstance(child, dict):
                    for example in child.values():
                        if isinstance(example, dict) and "$ref" in example:
                            walk({"$ref": example["$ref"]}, current)
                elif key not in {"$ref", "example", "default", "enum", "const"}:
                    walk(child, current)

    walk(document, path.resolve())


def documented_routes(path):
    with path.open() as stream:
        document = yaml.load(stream, Loader=UniqueLoader)
    if not isinstance(document, dict) or "openapi" not in document or "paths" not in document:
        raise ValueError(str(path) + ": openapi와 paths가 필요합니다.")
    validate_references(path, document)
    routes = set()
    for route, item in document["paths"].items():
        if not isinstance(route, str) or not route.startswith("/"):
            raise ValueError(str(path) + ": 잘못된 API 경로: " + str(route))
        if not isinstance(item, dict) or "$ref" in item:
            raise ValueError(str(path) + ": paths의 $ref는 현재 정적 검사 범위 밖입니다.")
        routes.update((key, route) for key in item if key in HTTP_METHODS)
    return routes


def check(root):
    errors, counts = [], []
    for service in sorted((root / "services").glob("*-service")):
        actual = {}
        for source in sorted((service / "src/main/java").rglob("*.java")):
            for operation, locations in controller_routes(source.read_text(), source.relative_to(root)).items():
                actual.setdefault(operation, []).extend(locations)
        spec = root / "contracts/api" / (service.name + ".yaml")
        if not actual and not spec.exists():
            continue
        if not spec.exists():
            errors.append(service.name + ": OpenAPI 문서가 없습니다.")
            continue
        expected = documented_routes(spec)
        for verb, route in sorted(actual.keys() - expected):
            errors.append(service.name + ": 문서 누락 " + verb.upper() + " " + route
                          + " (" + ", ".join(actual[(verb, route)]) + ")")
        for verb, route in sorted(expected - actual.keys()):
            errors.append(service.name + ": 코드에 없는 문서 API " + verb.upper() + " " + route)
        counts.append(service.name + ": " + str(len(actual)) + "개 매핑")
    return errors, counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    try:
        errors, counts = check(args.root)
    except (ValueError, yaml.YAMLError) as error:
        print("API 정적 검사 실패: " + str(error))
        return 1
    print("\n".join(counts))
    if errors:
        print("\n".join(errors))
        return 1
    print("API 경로·HTTP 메서드가 OpenAPI 문서와 일치합니다. 실행 결과는 검사하지 않습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
