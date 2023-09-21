import os
import time
import signal
from subprocess import Popen

jar_name = "TwoPhase-1.1-noabort-demo-jar-with-dependencies.jar"

def run():
    print("--- Run server ---")
    server_process = Popen([
        "java",
        "-cp",
        f"target/{jar_name}",
        "org.lbee.Server",
        "6869"])

    # Wait the server run, if comment this line, maye some manager running before the server, leading to error
    # This behavior might be interesting for trace validation
    time.sleep(2)

    print("--- Run TM client ---")
    tm_process = Popen([
        "java",
        "-cp",
        f"target/{jar_name}",
        "org.lbee.Client",
        "localhost", "6869", "tm", ""])

    print("--- Run RM clients ---")
    rm_processes = []
    for i in range(2):
        print(f"Run rm{i} client")
        rm_process = Popen([
            "java",
            "-cp",
            f"target/{jar_name}",
            "org.lbee.Client",
            "localhost", "6869", "rm", f"rm-{i}"])

        rm_processes.append(rm_process)


    # Wait all client are finished
    tm_process.wait()
    for rm_process in rm_processes:
        rm_process.wait()

    server_process.terminate()
    tm_process.terminate()
    for rm_process in rm_processes:
        rm_process.terminate()

    # Kill server
    os.kill(server_process.pid, signal.SIGINT)

if __name__ == "__main__":
    run()