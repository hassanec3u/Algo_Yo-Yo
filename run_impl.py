import os
import argparse
import json
import time
import signal
from subprocess import Popen

jar_name = "TwoPhase-1.1-noabort-demo-jar-with-dependencies.jar"

def get_files(config):
    files = []
    for line in config:
        if "id" in line:  # Vérifie si l'objet JSON contient une clé "id"
            files.append(str(line["id"]) + ".ndjson")
    return files

def read_ndjson(config_file):
    with open(config_file, 'r') as f:
        return [json.loads(line) for line in f]
    
def run(agents):

    print("--- Run server ---")
    server_process = Popen([
        "java",
        "-cp",
        f"target/{jar_name}",
        "org.lbee.network.Server",
        "6869", "unordered"])

    # Wait the server to run, if not some manager might start 
    # running before the server, leading to an error
    # This behavior might be interesting for trace validation
    time.sleep(0.5)

  # Now you can process the config data
    for agent_data in agents:
        agent_id = agent_data['id']
        sortant = agent_data['sortant']
        entrant = agent_data['entrant']
        # Process this agent data, e.g., create an Agent object or pass it to run()
        agent = {'id': agent_id, 'sortant': sortant, 'entrant': entrant}

        print("--- Run Agent ---")
        agent_processes = []
        duration = 10

        args = [
            "java",
            "-cp",
            f"target/{jar_name}",
            "org.lbee.Client",
            "localhost", "6869", f"{agent_id}",f"{entrant}",f"{sortant}"]
        rm_process = Popen(args)
            # if duration is the same for all RMs the bug (in TM) has much less chances to appear
        duration += 40
        agent_processes.append(rm_process)

    # Wait for all clients to be finished
    for rm_process in agent_processes:
        rm_process.wait()
    # terminate
    server_process.terminate()
    for rm_process in agent_processes:
        rm_process.terminate()
    # Kill server
    os.kill(server_process.pid, signal.SIGINT)


if __name__ == "__main__":
    # Read program args
    parser = argparse.ArgumentParser(description="")
    parser.add_argument('--config', type=str, required=False,
                        default="conf.ndjson", help="Config file")
    args = parser.parse_args()

    # Read config and run
    agents = read_ndjson(args.config)
    run(agents)
