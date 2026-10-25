"""Offline AWS stub tests. No real credentials, network or recordings are used."""
import base64
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'r2-recordings.sh'
STAMP = '20260930T235959Z'
FIRST = f'aks-v2/2026-09-30/{STAMP}-9999.aks.gz'
SECOND = f'aks-v2/2026-10-01/{STAMP}-10000.aks.gz'
TELEMETRY = 'imsa-telemetry/2026-10-01/20261001T000000Z-0000.aks.gz'

STUB = r'''
import base64, json, os, pathlib, sys
args=sys.argv[1:]
config=json.loads(pathlib.Path(os.environ['STUB_CONFIG']).read_text())
with open(os.environ['STUB_LOG'],'a') as f:
    f.write(json.dumps(dict(args=args, profile=os.environ.get('AWS_PROFILE'), token=os.environ.get('AWS_SESSION_TOKEN'), key=os.environ.get('AWS_ACCESS_KEY_ID'), secret=os.environ.get('AWS_SECRET_ACCESS_KEY'))) + '\n')
assert args[:3] == ['--endpoint-url','https://r2.invalid','s3'], args
if args[3]=='ls':
    if config.get('fail_list'): print('simulated listing failure',file=sys.stderr); sys.exit(12)
    prefix=args[-1].removeprefix('s3://fixture-bucket/')
    for key, value in sorted(config['objects'].items()):
        if key.startswith(prefix): print('2026-10-01 00:00:00',len(base64.b64decode(value)),key)
elif args[3]=='cp':
    key=args[-2].removeprefix('s3://fixture-bucket/')
    destination=pathlib.Path(args[-1])
    if config.get('fail_copy'):
        destination.write_bytes(b'partial download');sys.exit(13)
    destination.write_bytes(base64.b64decode(config['objects'][key]))
else: raise AssertionError(args)
'''


class R2RecordingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.script = self.root / 'r2-recordings.sh'
        shutil.copyfile(SCRIPT, self.script)
        self.bin = self.root / 'bin'; self.bin.mkdir()
        stub = self.bin / 'aws'
        stub.write_text('#!' + sys.executable + '\n' + STUB); stub.chmod(0o755)
        self.config = self.root / 'fixtures.json'
        self.log = self.root / 'calls.jsonl'
        self.out = self.root / 'recordings with spaces'
        self.env = dict(os.environ, PATH=str(self.bin)+os.pathsep+os.environ['PATH'], R2_ENDPOINT='https://r2.invalid', R2_ACCESS_KEY_ID='fixture-key', R2_SECRET_ACCESS_KEY='fixture-secret', ALKAMELV2_RECORDING_BUCKET='fixture-bucket', AWS_PROFILE='must-not-be-used', AWS_SESSION_TOKEN='must-not-be-used', STUB_CONFIG=str(self.config), STUB_LOG=str(self.log))
        self.fixtures({FIRST: b'first\n', SECOND: b'second\n'})

    def fixtures(self, objects, **flags):
        self.config.write_text(json.dumps(dict(objects={key:base64.b64encode(gzip.compress(value)).decode() for key,value in objects.items()}, **flags)))

    def run_script(self, *args):
        return subprocess.run(['bash', str(self.script), *args], env=self.env, capture_output=True, text=True, timeout=10)

    def calls(self):
        return [json.loads(s) for s in self.log.read_text().splitlines()] if self.log.exists() else []

    def target(self, key): return self.out / key.replace('/', '_')

    def test_help_does_not_load_credentials_or_run_aws(self):
        marker = self.root / 'sourced'
        (self.root / '.r2.env').write_text('touch "' + str(marker) + '"\n')
        result = self.run_script('--help')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('--merge', result.stdout)
        self.assertFalse(marker.exists()); self.assertEqual(self.calls(), [])

    def test_list_summarizes_and_overrides_ambient_aws_identity(self):
        result = self.run_script('list', 'aks')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('aks-v2/2026-09-30', result.stdout)
        call = self.calls()[0]
        self.assertEqual((call['key'],call['secret']),('fixture-key','fixture-secret'))
        self.assertIsNone(call['profile']); self.assertIsNone(call['token'])

    def test_cross_midnight_merge_sorts_numeric_sequence_and_handles_spaces(self):
        result = self.run_script('get', 'aks', '--connection', STAMP, '--merge', '--out', str(self.out))
        self.assertEqual(result.returncode, 0, result.stderr)
        merged = self.out / f'aks-v2_2026-09-30_{STAMP}.aks.gz'
        self.assertEqual(gzip.decompress(merged.read_bytes()), b'first\nsecond\n')
        prefixes=[c['args'][-1] for c in self.calls() if c['args'][3]=='ls']
        self.assertEqual(prefixes,['s3://fixture-bucket/aks-v2/2026-09-30/','s3://fixture-bucket/aks-v2/2026-10-01/'])
        self.assertEqual(list(self.out.glob('.r2-*')), [])

    def test_listing_failure_is_not_reported_as_empty(self):
        self.fixtures({}, fail_list=True)
        for args in [('list','aks'),('get','all','--out',str(self.out))]:
            with self.subTest(args=args):
                result = self.run_script(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('simulated listing failure',result.stderr)
                self.assertNotIn('nothing found',result.stderr)

    def test_failed_download_is_never_kept_or_reported_as_success(self):
        self.fixtures({FIRST:b'first'}, fail_copy=True)
        result = self.run_script('get','all','--out',str(self.out))
        self.assertNotEqual(result.returncode,0)
        self.assertFalse(self.target(FIRST).exists())
        self.assertEqual(list(self.out.iterdir()),[])
        self.assertNotIn('got ',result.stdout)
        self.assertEqual(sum(c['args'][3]=='ls' for c in self.calls()),1)

    def test_invalid_gzip_download_is_rejected(self):
        self.config.write_text(json.dumps({'objects':{FIRST:base64.b64encode(b'not gzip').decode()}}))
        result = self.run_script('get','aks','--out',str(self.out))
        self.assertNotEqual(result.returncode,0)
        self.assertFalse(self.target(FIRST).exists())
        self.assertEqual(list(self.out.iterdir()),[])

    def test_corrupt_existing_file_is_replaced_then_valid_file_is_reused(self):
        self.fixtures({FIRST:b'repaired'})
        self.out.mkdir(); self.target(FIRST).write_bytes(b'partial')
        first = self.run_script('get','aks','--out',str(self.out))
        self.assertEqual(first.returncode,0,first.stderr)
        self.assertEqual(gzip.decompress(self.target(FIRST).read_bytes()),b'repaired')
        self.log.unlink()
        second = self.run_script('get','aks','--out',str(self.out))
        self.assertEqual(second.returncode,0,second.stderr)
        self.assertFalse(any(c['args'][3]=='cp' for c in self.calls()))
        self.assertIn('have ',second.stdout)

    def test_failed_replacement_preserves_existing_file(self):
        self.fixtures({FIRST:b'repaired'},fail_copy=True)
        self.out.mkdir(); self.target(FIRST).write_bytes(b'existing partial')
        result = self.run_script('get','aks','--out',str(self.out))
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(self.target(FIRST).read_bytes(),b'existing partial')
        self.assertEqual(list(self.out.glob('.r2-*')),[])

    def test_all_continues_past_empty_feed(self):
        self.fixtures({TELEMETRY:b'telemetry'})
        result = self.run_script('get','all','--out',str(self.out))
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(gzip.decompress(self.target(TELEMETRY).read_bytes()),b'telemetry')

    def test_empty_bucket_returns_error(self):
        self.fixtures({})
        result = self.run_script('get','all','--out',str(self.out))
        self.assertNotEqual(result.returncode,0)
        self.assertIn('nothing found',result.stderr)

    def test_bad_arguments_do_not_call_aws(self):
        for args in [('get','aks','2026-02-31'),('get','aks','--out'),('get','aks','--out','--merge'),('get','aks','--connection','bad'),('list','aks','2026-10-01','extra')]:
            with self.subTest(args=args):
                result=self.run_script(*args)
                self.assertNotEqual(result.returncode,0)
                self.assertEqual(self.calls(),[])

    def test_nonrecording_objects_are_not_downloaded(self):
        self.fixtures({'aks-v2/2026-09-30/not-a-recording.gz':b'ignored',FIRST:b'valid'})
        result=self.run_script('get','aks','--out',str(self.out))
        self.assertEqual(result.returncode,0,result.stderr)
        copies=[c['args'][-2] for c in self.calls() if c['args'][3]=='cp']
        self.assertEqual(copies,['s3://fixture-bucket/'+FIRST])


if __name__ == '__main__': unittest.main()
