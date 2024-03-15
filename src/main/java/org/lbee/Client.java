package org.lbee;

import org.lbee.instrumentation.trace.TLATracer;
import org.lbee.instrumentation.clock.ClockException;
import org.lbee.instrumentation.clock.ClockFactory;
import org.lbee.network.NetworkManager;
import org.lbee.protocol.AgentYoyo;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Client manager (transaction manager or resource manager)
 */
public class Client {

    public static void main(String[] args) throws IOException, ClockException {

//        if (args.length < 5) {
//            System.out.println("Missing arguments. hostname, port, type={tm, rm}, rmName, duration expected.");
//            return;
//        }

        // Get hostname, port,
        final String hostname = args[0];
        final int port = Integer.parseInt(args[1]);
        final String agentId = args[2];
        final String[] entrants = conversionEnString(args[3]);
        final String[] sortants = conversionEnString(args[4]);


        //final int duration = Integer.parseInt(args[args.length - 1]);
        // final JsonObject jsonConfig = ConfigurationManager.read("twophase.ndjson.conf");
        // System.out.println(jsonConfig);
        // final Configuration config = new Configuration(jsonConfig);

        try (Socket socket = new Socket(hostname, port)) {
            NetworkManager networkManager = new NetworkManager(socket);
            //  TLATracer spec = TLATracer.getTracer(managerName + ".ndjson",
            //  ClockFactory.getClock(ClockFactory.FILE, "twophase.clock"));


            // Création de l'agent avec ses voisins. Ici, on doit transformer les IDs des voisins en véritables objets Agent.
            // Exemple simplifié sans la transformation réelle des voisins
            AgentYoyo agent = new AgentYoyo(networkManager, agentId, new HashSet<>(), new HashSet<>());

            for (String entrant : entrants){
                agent.ajouterEntrant(entrant);
            }

            for (String sortant : sortants){
                agent.ajouterSortant(sortant);
            }

            agent.run();


            // Send bye to server (kill the server thread)
            // networkManager.sendRaw("bye");
        } catch (UnknownHostException ex) {
            System.out.println("Server not found: " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("I/O error: " + ex.getMessage());
        }
    }

    public static String[] conversionEnString(String str){
        // Supprimer les crochets et les espaces
        String cleanedInput = str.replaceAll("[\\[\\]\\s]", "");

        // Diviser la chaîne en utilisant la virgule comme délimiteur
        String[] parts = cleanedInput.split(",");

        // Créer un tableau de chaînes de caractères pour stocker les résultats
        String[] result = new String[parts.length];

        // Convertir chaque élément en chaîne de caractères
        for (int i = 0; i < parts.length; i++) {
            result[i] = parts[i];
        }
        return result;

    }
}
