"""Every sample entry-list PDF must parse to output that validates against
schemas/entries.schema.json — the machine-readable half of SCHEMA.md.

The schema is strict (additionalProperties: false) so an accidental field
rename, type drift, or new field lands here first, not in a consumer.
"""
from pathlib import Path

import pytest

import parse_entry_list as p

HERE = Path(__file__).parent
SCHEMA_PATH = HERE / "schemas" / "entries.schema.json"
# Entry-list samples are every PDF that isn't a points sheet.
SAMPLES = sorted(f for f in (HERE / "samples").glob("*.pdf") if "Points" not in f.name)

pytestmark = pytest.mark.skipif(not SAMPLES, reason="no sample PDFs present")


@pytest.fixture(scope="module")
def validator():
    jsonschema = pytest.importorskip("jsonschema")
    import json

    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(schema)


@pytest.mark.parametrize("pdf", SAMPLES, ids=lambda f: f.name)
def test_output_matches_schema(pdf, validator):
    doc = p.parse(pdf, None)
    errors = sorted(validator.iter_errors(doc), key=lambda e: e.json_path)
    assert not errors, "\n".join(f"{e.json_path}: {e.message}" for e in errors)
