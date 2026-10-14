import tempfile
import unittest
from pathlib import Path

from check_structure import validate_references, check, controller_routes, documented_routes


class ApiStructureCheckTest(unittest.TestCase):
    def test_arrays_request_methods_and_class_prefixes(self):
        source = '''@RestController @RequestMapping({"/v1/a", "/v1/b"}) class C {
            @RequestMapping(path={"/{id}", "/mine"}, method={RequestMethod.GET, RequestMethod.PATCH})
            public void read() {}
        }'''
        routes = controller_routes(source, Path("C.java"))
        self.assertEqual(set(routes), {
            (verb, base + path) for verb in ["get", "patch"]
            for base in ["/v1/a", "/v1/b"] for path in ["/{id}", "/mine"]
        })

    def test_comments_and_documentation_strings_are_not_routes(self):
        source = '''// @GetMapping("/v1/fake")
        @RestController class C {
            @Operation(description="@PostMapping(\\\"/v1/fake\\\")")
            @GetMapping("/v1/real") public void read() {}
        }'''
        self.assertEqual(set(controller_routes(source, Path("C.java"))), {("get", "/v1/real")})

    def test_trailing_slash_and_conditional_controller_are_not_hidden(self):
        source = '''@Profile("local") @RestController @RequestMapping("/v1/a") class C {
            @GetMapping public void list() {}
            @GetMapping("/") public void missingId() {}
        }'''
        self.assertEqual(set(controller_routes(source, Path("C.java"))), {
            ("get", "/v1/a"), ("get", "/v1/a/")
        })

    def test_unknown_constant_inheritance_and_invalid_syntax_fail(self):
        for source in [
            '@RestController class C { @GetMapping(PATH) public void read() {} }',
            '@RestController class C implements Api { }',
            '@RestController class C { @GetMapping("/v1/a") public void read( {} }',
            '@RestController class C { @CustomMapping("/v1/a") public void read() {} }',
        ]:
            with self.subTest(source=source), self.assertRaises(ValueError):
                controller_routes(source, Path("C.java"))

    def test_versions_and_unversioned_paths_are_supported(self):
        source = '''@RestController class C {
            @GetMapping("/v2/products") public void list() {}
            @GetMapping("/health") public void health() {}
        }'''
        self.assertEqual(set(controller_routes(source, Path("C.java"))), {
            ("get", "/v2/products"), ("get", "/health")
        })
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "api.yaml")
            path.write_text("openapi: 3.1.0\npaths:\n  /v2/products:\n    get: {}\n  /health:\n    get: {}\n")
            self.assertEqual(documented_routes(path), {("get", "/v2/products"), ("get", "/health")})

    def test_yaml_duplicate_routes_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "api.yaml")
            path.write_text('openapi: 3.1.0\npaths:\n  /v1/a:\n    get: {}\n  /v1/a:\n    post: {}\n')
            with self.assertRaisesRegex(ValueError, "중복 키"):
                documented_routes(path)

    def test_missing_and_stale_operations_are_detected_in_both_directions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java = root / "services/example-service/src/main/java/C.java"
            java.parent.mkdir(parents=True)
            java.write_text('@RestController class C { @GetMapping("/v1/a") public void read() {} }')
            spec = root / "contracts/api/example-service.yaml"
            spec.parent.mkdir(parents=True)
            spec.write_text('openapi: 3.1.0\npaths:\n  /v1/a:\n    post: {}\n')
            errors, _ = check(root)
            self.assertEqual(len(errors), 2)
            self.assertTrue(any("문서 누락 GET /v1/a" in e for e in errors))
            self.assertTrue(any("코드에 없는 문서 API POST /v1/a" in e for e in errors))
            spec.write_text('openapi: 3.1.0\npaths:\n  /v1/a:\n    get: {}\n')
            self.assertEqual(check(root)[0], [])



class ReferenceTests(unittest.TestCase):
    def test_relative_references_and_recursive_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "api.yaml")
            Path(directory, "common.yaml").write_text(
                "schemas:\n  Node:\n    properties:\n      next: {$ref: '#/schemas/Node'}\n"
                "  'a/b~c': {type: string}\n")
            validate_references(path, {"components": {"schemas": {
                "Node": {"$ref": "common.yaml#/schemas/Node"},
                "Escaped": {"$ref": "common.yaml#/schemas/a~1b~0c"}}}})

    def test_missing_file_pointer_and_nonlocal_reference_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "api.yaml")
            Path(directory, "common.yaml").write_text("schemas: {}\n")
            for ref in ["missing.yaml#/schemas/Error", "common.yaml#/schemas/Error",
                        "https://example.com/spec.yaml#/Error", "common.yaml#anchor"]:
                with self.subTest(ref=ref), self.assertRaises(ValueError):
                    validate_references(path, {"$ref": ref})

    def test_examples_are_not_interpreted_as_document_references(self):
        validate_references(Path("api.yaml"), {
            "example": {"$ref": "business-value"},
            "examples": {"sample": {"value": {"$ref": "business-value"}}}})
        with self.assertRaises(ValueError):
            validate_references(Path("api.yaml"), {
                "examples": {"shared": {"$ref": "missing.yaml#/example"}}})

    def test_shared_error_and_live_restriction_are_preserved(self):
        import yaml
        root = Path(__file__).resolve().parents[3]
        common = yaml.safe_load((root / "contracts/api/common.yaml").read_text())
        error = common["components"]["schemas"]["ApiError"]
        self.assertEqual(error["required"], ["code", "message", "requestId"])
        self.assertEqual(error["properties"]["requestId"]["type"], ["string", "null"])
        live = yaml.safe_load((root / "contracts/api/live-service.yaml").read_text())
        codes = live["components"]["schemas"]["ApiError"]["allOf"][1]["properties"]["code"]["enum"]
        self.assertEqual(codes, ["INVALID_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND",
                                 "CONFLICT", "SERVICE_UNAVAILABLE", "INTERNAL_ERROR"])

if __name__ == "__main__":
    unittest.main()
