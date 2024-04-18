import json
import os
import subprocess
import run_impl
import trace_merger
import tla_trace_validation
import argparse
import ndjson
import json
import re


# fonction pour mettre à jour TLA+ selon le fichier conf.ndjson
def update_tla_file(config_path, tla_path):
    # Lire le fichier conf.ndjson
    with open(config_path, 'r') as file:
        json_lines = file.readlines()
    # Extraire les noeuds et les arêtes
    nodes = set()
    edges = set()
    for line in json_lines:
        entry = json.loads(line)
        node_id = entry['id']
        nodes.add(node_id)
        for dest in entry['sortant']:
            edges.add((node_id, dest))

    # Convertir les ensembles en chaînes de caractères TLA+.
    nodes_str = "{" + ", ".join(map(str, sorted(nodes))) + "}"
    edges_str = "{" + ", ".join(["{" + str(src) + ", " + str(dest) + "}" for src, dest in sorted(edges)]) + "}"

    # Lire le contenu existant de trace.tla
    with open(tla_path, 'r') as tla_file:
        tla_contents = tla_file.read()

    # Remplacer les sections TraceNodes et TraceEdges dans le contenu.
    new_tla_contents = re.sub(r"TraceNodes ==\n\s*{.*?}", f"TraceNodes ==\n    {nodes_str}", tla_contents, flags=re.DOTALL)
    new_tla_contents = re.sub(r"TraceEdges ==\n\s*{.*?}", f"TraceEdges ==\n    {edges_str}", new_tla_contents, flags=re.DOTALL)

    # Écrire le nouveau contenu dans trace.tla
    with open(tla_path, 'w') as tla_file:
        tla_file.write(new_tla_contents)









def read_json(config_file):
    with open(config_file, 'r') as f:
        # Ignorer les deux premières lignes( Nodes et Edges)
        next(f)
        next(f)
        # Lire le reste du fichier NDJSON
        return [json.loads(line) for line in f]
def get_files(config):
    files = []
    for line in config:
        if "id" in line:  # Vérifie si l'objet JSON contient une clé "id"
            files.append(str(line["id"]) + ".ndjson")
    return files

parser = argparse.ArgumentParser("")
parser.add_argument('-c', '--compile', type=bool, action=argparse.BooleanOptionalAction)
parser.add_argument('--config', type=str, required=False, default="conf.ndjson", help="Config file")
args = parser.parse_args()

config = read_json(args.config)
files = get_files(config)


# Update TLA+ config
print("# Update TLA+ Configuration.\n")
# Utilisation de la fonction
update_tla_file('conf.ndjson', 'spec/YoYoNoPruningTrace.tla')

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
tla_trace_validation.run_tla("spec/YoYoNoPruningTrace.tla","trace.ndjson","conf.ndjson")

# print("End pipeline.")