package org.lbee.protocol;

import org.lbee.instrumentation.clock.ClockException;
import org.lbee.instrumentation.clock.ClockFactory;
import org.lbee.instrumentation.clock.InstrumentationClock;
import org.lbee.instrumentation.trace.TLATracer;
import org.lbee.instrumentation.trace.VirtualField;
import org.lbee.network.NetworkManager;
import org.lbee.network.TimeOutException;

import java.io.IOException;
import java.util.*;

public class AgentYoyo {

    private String id;

    private Message msgEnAvance;
    private Set<String> entrants;
    private Set<String> sortants;
    private EtatNoeud etat;
    private boolean aRecuToutSesEntrants;
    private boolean aRecuToutSesSortants;
    private Set<String> noeud_a_inverser;
    private int compteurMsgEntrants;
    private int compteurMsgDeSortant;
    private boolean aRecuNO;
    private Set<String> parents_ayant_valeur_min;
    private String mini_actuel;
    private String phase;
    final NetworkManager networkManager;
    private InstrumentationClock temps;

    // tracing
    private final TLATracer tracer;
    private final VirtualField traceMessages;
    private final VirtualField traceInGoing;
    private final VirtualField traceOutGoing;
    private final VirtualField tracePhase;

    public AgentYoyo(NetworkManager networkManager, String id, Set<String> in, Set<String> out, TLATracer tracer) {
        this.networkManager = networkManager;
        this.id = id;
        this.entrants = in;
        this.sortants = out;
        this.etat = EtatNoeud.INCONNU;
        this.aRecuToutSesEntrants = false;
        this.aRecuToutSesSortants = false;
        aRecuNO = false;
        this.noeud_a_inverser = new HashSet<>();
        this.compteurMsgEntrants = 0;
        this.compteurMsgDeSortant = 0;
        this.parents_ayant_valeur_min = new HashSet<>();
        this.mini_actuel = id;

        try {
            temps = ClockFactory.getClock(2, "clock");
        } catch (ClockException e) {
            throw new RuntimeException(e);
        }

        this.traceMessages = tracer.getVariableTracer("mailbox").getField(this.id);
        ;
        this.traceInGoing = tracer.getVariableTracer("incoming").getField(this.id);
        this.traceOutGoing = tracer.getVariableTracer("outgoing").getField(this.id);
        this.tracePhase = tracer.getVariableTracer("phase").getField(this.id);
        this.tracer = tracer;
    }

    public void ajouterEntrant(String agent) {
        if (!agent.isEmpty()) {
            entrants.add(agent);
        }
    }

    public void ajouterSortant(String agent) {
        if (!agent.isEmpty()) {
            sortants.add(agent);
        }
    }

    public void mise_a_jour_etat() {
        if (this.entrants.isEmpty()) {
            this.etat = EtatNoeud.SOURCE;
        }
        if (this.sortants.isEmpty()) {
            this.etat = EtatNoeud.PUITS;
            aRecuToutSesEntrants = false;
        }
        if (!this.sortants.isEmpty() && !this.entrants.isEmpty()) {
            this.etat = EtatNoeud.INTERNE;
        }
    }

    //on Inverse les nœuds à la fin de la phase -YO
    public void inverse_node() {
        // System.out.println("id: "+ id + " mon etat: " + etat + " "+ entrants + " " + sortants + " " + noeud_a_inverser);

        for (String node : noeud_a_inverser) {
            if (entrants.contains(node)) {
                entrants.remove(node);
                sortants.add(node);
            } else if (sortants.contains(node)) {
                sortants.remove(node);
                entrants.add(node);
            }
        }
        noeud_a_inverser.clear();
    }


    public void phase_yo_down() throws IOException {
        phase = "down";

        // trace the down phase
        this.tracePhase.set(this.phase.toLowerCase(Locale.ROOT));

        if (msgEnAvance != null) {
            System.out.println("test_______________________________ " + msgEnAvance + " <=> " + phase);
            handleMessage(msgEnAvance);
            msgEnAvance = null;
        }

        if (this.etat == EtatNoeud.SOURCE) {
            while (!this.aRecuToutSesEntrants) {
                // Attendre que tous les messages soient reçus
                attendreMessage();
            }
            //les sources envoient leur id aux agents sortants
            for (String agent : this.sortants) {
                //transfere son id à chaque agent dans ses sortants
                networkManager.send(new Message(this.id, agent, TypeMessage.ID.toString(), phase, this.id, temps.getNextTime()));
            }
            //tracing
            traceMessages.add(Map.of("phase", this.phase, "sndr", this.id, "val", mini_actuel));

            tracer.log("DownSource");


        } else {
            //cas ou on est dans un noeud interne/puits, on attend d'avoir recu tout les id des agents rentrant
            while (!this.aRecuToutSesEntrants) {
                // Attendre que tous les messages soient reçus
                attendreMessage();
            }
            //les internes envoie leur id a leur sortant
            for (String agent : this.sortants) {
                networkManager.send(new Message(this.id, agent, TypeMessage.ID.toString(), phase, mini_actuel, ClockFactory.FILE));
            }
            //tracing
            traceMessages.add(Map.of("phase", this.phase, "sndr", this.id, "val", mini_actuel));

            tracer.log("DownOther");
        }
        aRecuToutSesEntrants = false;
    }


    public void phase_yo_up() throws IOException {
        phase = "up";

        // trace the down phase
        this.tracePhase.set(this.phase.toLowerCase(Locale.ROOT));

        //cas ou nous somme un PUIT
        if (this.etat == EtatNoeud.PUITS) {
            //envoyer YES a tous les puits ayant des parents avec l'id minimum
            for (String agent : this.parents_ayant_valeur_min) {
                networkManager.send(new Message(this.id, agent, TypeMessage.YES.toString(), this.phase, "-1", temps.getNextTime()));
            }
            //On envoie NO au reste
            for (String agent : this.entrants) {
                if (!this.parents_ayant_valeur_min.contains(agent)) {
                    networkManager.send(new Message(this.id, agent, TypeMessage.NO.toString(), this.phase, "-1", temps.getNextTime()));
                    this.noeud_a_inverser.add(agent);
                }
            }
        }

        //cas ou nous somme dans un noued interne
        if (this.etat == EtatNoeud.INTERNE) {
            //on attends qu'ils recoit les messages de ses sortants
            while (!aRecuToutSesSortants) {
                attendreMessage();
            }

            //si on recoit un No des sortant, on le proapage dans les entrants
            if (this.aRecuNO) {
                for (String agent : this.entrants) {
                    networkManager.send(new Message(this.id, agent, TypeMessage.NO.toString(), this.phase, "-1", temps.getNextTime()));
                    this.noeud_a_inverser.add(agent);
                }
            } else {

                //on envoie Yes au parents ayant la valeur minimum
                for (String agent : this.parents_ayant_valeur_min) {
                    networkManager.send(new Message(this.id, agent, TypeMessage.YES.toString(), this.phase, "-1", temps.getNextTime()));
                }

                //Et NO aux parents n'ayant pas la valeur minimum
                for (String agent : this.entrants) {
                    if (!this.parents_ayant_valeur_min.contains(agent)) {
                        networkManager.send(new Message(this.id, agent, TypeMessage.NO.toString(), this.phase, "-1", temps.getNextTime()));
                        this.noeud_a_inverser.add(agent);

                    }
                }
            }
        }

        if (etat == EtatNoeud.SOURCE) {
            while (!aRecuToutSesSortants) {
                attendreMessage();
            }
            //on met à true pour demarrer la prochaine iteration
            aRecuToutSesEntrants = true;
        }

        //apres avoir tout envoyé, on remet à false
        this.aRecuNO = false;
        aRecuToutSesSortants = false;
    }


    public void run() {
        mise_a_jour_etat();

        if (etat == EtatNoeud.SOURCE) {
            aRecuToutSesEntrants = true;
        }

        while (true) {
            try {

                System.out.println("id: " + id + " mon etat: " + etat + " " + entrants + " " + sortants + " mini: " + mini_actuel);


                phase_yo_down();

                phase_yo_up();

                inverse_node();
                mise_a_jour_etat();

                System.out.println();


            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void attendreMessage() throws IOException {
        Message message = null;
        try {
            message = networkManager.receive(id, 0);
        } catch (TimeOutException e) {
            throw new RuntimeException(e);
        }
        if (message != null) {
            this.handleMessage(message);
        }
    }

    private void handleMessage(Message message) throws IOException {

        //Verifie si le message nous appartient
        if (message.getTo().equals(id)) {

            if (message.getPhase().equals(this.phase)) {


                System.out.println("\u001B[32m   Message received: " + message + "\u001B[0m");

                //cas ou on recoit un ID
                if (message.getType().equals(TypeMessage.ID.toString())) {
                    //si on a le meme id, on le rajoute dans l'ensemble des parents ayant la valeur min
                    if (Integer.parseInt(message.getContent()) == Integer.parseInt(mini_actuel)) {
                        parents_ayant_valeur_min.add(message.getFrom());
                    }

                    //si la valeur recu est plus petite, on efface la liste et on le rajoute
                    if (Integer.parseInt(message.getContent()) < Integer.parseInt(mini_actuel)) {
                        parents_ayant_valeur_min.clear();
                        parents_ayant_valeur_min.add(message.getFrom());
                        mini_actuel = message.getContent();
                    }

                    this.compteurMsgEntrants++;
                    if (compteurMsgEntrants == entrants.size()) {
                        this.aRecuToutSesEntrants = true;
                        compteurMsgEntrants = 0;
                    }
                }
            } else {
                msgEnAvance = message;
            }

            //cas ou on recoit un YES
            if (message.getType().equals(TypeMessage.YES.toString())) {
                compteurMsgDeSortant++;
                //on a recu tout les reponses des voisins sortants
                if (compteurMsgDeSortant == sortants.size()) {
                    aRecuToutSesSortants = true;
                    compteurMsgDeSortant = 0;
                }
            }

            //cas ou on recoit un NO
            if (message.getType().equals(TypeMessage.NO.toString())) {
                noeud_a_inverser.add(message.getFrom());
                this.aRecuNO = true;
                this.compteurMsgDeSortant++;
                if (compteurMsgDeSortant == sortants.size()) {
                    aRecuToutSesSortants = true;
                    compteurMsgDeSortant = 0;
                }
            }
        }

    }
}

