import os
import subprocess
import run_impl
import trace_merger
import tla_trace_validation
import argparse
import ndjson

def read_json(filename):
    with open(filename) as f:
        return ndjson.load(f)

def get_files(config):
    files = []
    for line in config:
        files.append(str(line["id"]) + ".ndjson")
    return files

parser = argparse.ArgumentParser("")
parser.add_argument('-c', '--compile', type=bool, action=argparse.BooleanOptionalAction)
parser.add_argument('--config', type=str, required=False, default="conf.ndjson", help="Config file")
args = parser.parse_args()

config = read_json(args.config)
files = get_files(config)

# Clean up
print("# Clean up")
trace_files = files + ["trace.ndjson"]
print(f"Cleanup: {files}")
for trace_file in trace_files:
    if os.path.isfile(trace_file):
        os.remove(trace_file)

# Compile
if args.compile:
    print("# Package.\n")
    subprocess.run(["mvn", "package"])

print("# Start implementation.\n")
run_impl.run(config)

# Merge traces 
print("# Merge traces.\n")
trace_merger.run(files,sort=True, remove_meta=True, out="trace.ndjson")

# Validate trace
print("# Start TLA+ trace spec.\n")
tla_trace_validation.run_tla("spec/YoYoNoPruning.tla","trace.ndjson","conf.ndjson")

# print("End pipeline.")