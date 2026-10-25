#!/usr/bin/env python3
"""Pick (or create) the iPad simulator release.sh runs the unit tests on, boot
it, and print its xcodebuild destination.

Why this exists: a hard-coded `name=iPad Pro 11-inch (M5)` destination fails
whenever no simulator by that name exists, and a runtime's version alone does
not say which devices it can run — the iOS 27.1 runtime, for one, supports
only iPhone Duo, so `simctl create` with an iPad type fails there. This uses
the newest installed iOS runtime at or above project.yml's deployment target
that lists an iPad device type, and keeps a dedicated "PitPass Tests"
simulator on it so a release run never reinstalls the app on a simulator
someone is using.

Usage: test_simulator.py [--device-type "iPad Pro 11-inch (M5)"] [--name "PitPass Tests"]
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys


def version(value):
    parts = tuple(int(p) for p in value.split('.'))
    return parts + (0,) * (3 - len(parts))


def deployment_target():
    project = (Path(__file__).resolve().parent / 'project.yml').read_text()
    match = re.search(r'^\s+iOS:\s*["\']?([0-9.]+)', project, re.MULTILINE)
    if not match:
        raise ValueError('Cannot read the iOS deployment target from project.yml')
    return match.group(1)


def choose(inventory, minimum, preferred_type):
    """The (runtime, device type) to test on: the newest runtime that supports
    an iPad, then the preferred type, else an iPad Pro 11-inch, else any iPad."""
    runtimes = sorted(
        (r for r in inventory['runtimes']
         if r.get('isAvailable') and '.iOS-' in r['identifier']
         and version(r['version']) >= version(minimum)),
        key=lambda r: version(r['version']), reverse=True)
    for runtime in runtimes:
        ipads = [d for d in runtime.get('supportedDeviceTypes', []) if d.get('productFamily') == 'iPad']
        if not ipads:
            continue
        pick = (next((d for d in ipads if d['name'] == preferred_type), None)
                or next((d for d in reversed(ipads) if d['name'].startswith('iPad Pro 11-inch')), None)
                or ipads[-1])
        return runtime, pick
    raise ValueError(f'No installed iOS {minimum}+ simulator runtime supports an iPad. '
                     'Install one in Xcode Settings → Components, or set TEST_DESTINATION.')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--device-type', default='iPad Pro 11-inch (M5)')
    parser.add_argument('--name', default='PitPass Tests')
    args = parser.parse_args()

    inventory = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', '--json'], text=True))
    runtime, device_type = choose(inventory, deployment_target(), args.device_type)
    devices = inventory['devices'].get(runtime['identifier'], [])
    matches = sorted((d for d in devices if d.get('isAvailable') and d['name'] == args.name),
                     key=lambda d: (d['state'] != 'Booted', d['udid']))
    if matches:
        udid, booted = matches[0]['udid'], matches[0]['state'] == 'Booted'
        print(f'Reusing {args.name} on iOS {runtime["version"]}: {udid}', file=sys.stderr)
    else:
        udid = subprocess.check_output(
            ['xcrun', 'simctl', 'create', args.name, device_type['identifier'], runtime['identifier']],
            text=True).strip()
        booted = False
        print(f'Created {args.name} ({device_type["name"]}) on iOS {runtime["version"]}: {udid}', file=sys.stderr)
    if not booted:
        subprocess.run(['xcrun', 'simctl', 'boot', udid], check=True, stdout=sys.stderr)
    subprocess.run(['xcrun', 'simctl', 'bootstatus', udid, '-b'], check=True, stdout=sys.stderr)
    print(f'platform=iOS Simulator,id={udid}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, subprocess.CalledProcessError) as error:
        sys.exit(f'Simulator selection failed: {error}')
