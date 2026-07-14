import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from golden_cases import GoldenCaseError, load_golden_case


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RG01_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-01.json"


class GoldenCaseLoaderTests(unittest.TestCase):
    def test_loader_rejects_non_object_root(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "case.json"
            path.write_text("[]", encoding="utf-8")

            with self.assertRaisesRegex(GoldenCaseError, "root must be an object"):
                load_golden_case(path)

    def test_loader_rejects_unknown_schema(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "case.json"
            path.write_text(json.dumps({"schema_version": 2}), encoding="utf-8")

            with self.assertRaisesRegex(GoldenCaseError, "unsupported schema_version: 2"):
                load_golden_case(path)


if __name__ == "__main__":
    unittest.main()
