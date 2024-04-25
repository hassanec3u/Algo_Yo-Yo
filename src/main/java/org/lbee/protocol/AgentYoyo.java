package org.lbee.protocol;

import com.sun.jdi.connect.TransportTimeoutException;
import org.lbee.instrumentation.clock.ClockException;
import org.lbee.instrumentation.clock.ClockFactory;
import org.lbee.instrumentation.clock.InstrumentationClock;
import org.lbee.instrumentation.trace.TLATracer;
import org.lbee.instrumentation.trace.VirtualField;
import org.lbee.network.NetworkManager;
import org.lbee.network.TimeOutException;

import java.awt.geom.Area;
import java.io.IOException;
import java.util.*;

public class AgentYoyo {

    private String id;
    //pour stocker les messages recu de la ronde suivante
    private Set<String> incoming;
    private Set<String> outgoing;
    private EtatNoeud etat;
    private boolean aRecuToutSesincoming;
    private boolean aRecuToutSesoutgoing;
    private int compteurMsgincoming;
    private int compteurMsgSortant;
    private boolean aRecuUnNO;
    private Set<String> parents_ayant_valeur_min;
    private String mini_actuel;
    private String phase;
    final NetworkManager networkManager;
    private InstrumentationClock temps;
    private boolean isActive;
    private String prune;

    //on stocke les noeuds a inversé
    private Set<String> noeud_a_inverser;

    // tracing
    private final TLATracer tracer;
    private final VirtualField traceMessages;
    private final VirtualField traceInGoing;
    private final VirtualField traceOutGoing;
    private final VirtualField tracePhase;

    public AgentYoyo(NetworkManager networkManager, String id, Set<String> in, Set<String> out, TLATracer tracer) {
        this.networkManager = networkManager;
        this.id = id;
        this.incoming = in;
        this.outgoing = out;
        this.etat = EtatNoeud.INCONNU;
        this.aRecuToutSesincoming = false;
        this.aRecuToutSesoutgoing = false;
        this.aRecuUnNO = false;
        this.noeud_a_inverser = new HashSet<>();
        this.compteurMsgincoming = 0;
        this.compteurMsgSortant = 0;
        this.parents_ayant_valeur_min = new HashSet<>();
        this.mini_actuel = id;
        this.isActive = true;
        this.prune = "false";

        try {
            temps = ClockFactory.getClock(2, "clock");
        } catch (ClockException e) {
            throw new RuntimeException(e);
        }

        this.traceMessages = tracer.getVariableTracer("mailbox").getField(this.id);
        this.traceInGoing = tracer.getVariableTracer("incoming").getField(this.id);
        this.traceOutGoing = tracer.getVariableTracer("outgoing").getField(this.id);
        this.tracePhase = tracer.getVariableTracer("phase").getField(this.id);
        this.tracer = tracer;
    }

    public void ajouterEntrant(String agent) {
        if (!agent.isEmpty()) {
            incoming.add(agent);
        }
    }

    public void ajouterSortant(String agent) {
        if (!agent.isEmpty()) {
            outgoing.add(agent);
        }
    }

    public void mise_a_jour_etat() {
        if (this.incoming.isEmpty()) {
            this.etat = EtatNoeud.SOURCE;
        }
        if (this.outgoing.isEmpty()) {
            this.etat = EtatNoeud.PUITS;
        }
        if (!this.outgoing.isEmpty() && !this.incoming.isEmpty()) {
            this.etat = EtatNoeud.INTERNE;
        }
    }

    //on inverse les nœuds à la fin de la phase -YO
    public void inverse_node() {
        for (String node : noeud_a_inverser) {
            if (incoming.contains(node)) {
                incoming.remove(node);
                outgoing.add(node);
            } else if (outgoing.contains(node)) {
                outgoing.remove(node);
                incoming.add(node);
            }
        }
        noeud_a_inverser.clear();
    }


    public void phase_yo_down() throws IOException {
        phase = "down";

        if (this.etat == EtatNoeud.SOURCE) {
            diffusionID(this.outgoing, this.id, this.prune);
            tracer.log("DownSource", new Object[]{Integer.parseInt(id)});

        } else {
            //cas ou on est dans un noeud interne/puits, on attend d'avoir recu tout les id des noueds incoming
            while (!this.aRecuToutSesincoming) {
                // Attendre que tous les messages soient reçus avant de transferer le min actuel
                attendreMessage();
            }
            diffusionID(this.outgoing, mini_actuel, this.prune);
            //tracing
            tracer.log("DownOther", new Object[]{Integer.parseInt(id)});
        }

        //mise a jour variable pour la seconde ronde
        aRecuToutSesincoming = false;
    }

    public void phase_yo_up() throws IOException {
        phase = "up";

        // trace the down phase
        //cas ou le noeud actuel est un puit
        if (this.etat == EtatNoeud.PUITS) {
            //on envoie YES au incoming ayant envoyé val min
            diffusionReponse(this.parents_ayant_valeur_min, TypeMessage.YES.toString(),this.prune);

            //on cree la liste des gens qui ont envoyé No
            HashSet<String> noParent = new HashSet<>();
            for (String agent : this.incoming) {
                if (!this.parents_ayant_valeur_min.contains(agent)) {
                    noParent.add(agent);
                }
            }
            //On envoie NO au reste
            diffusionReponse(noParent, TypeMessage.NO.toString(), this.prune);
            noParent.clear();
            inverse_node();

            //tracing
            tracer.log("UpOther", new Object[]{Integer.parseInt(id)});

        }

        //cas ou nous somme dans un noued interne
        if (this.etat == EtatNoeud.INTERNE) {
            //on attends qu'ils recoit les messages de ses outgoing
            while (!aRecuToutSesoutgoing) {
                attendreMessage();
            }

            //si on recoit un No des outgoing, on le proapage dans les incoming
            if (this.aRecuUnNO) {
                diffusionReponse(this.incoming, TypeMessage.NO.toString(), this.prune);
                noeud_a_inverser.addAll(incoming);
            } else {

                //sinon on propage yes entrant ayant envoyé la val min
                diffusionReponse(this.parents_ayant_valeur_min, TypeMessage.YES.toString(), this.prune);

                //Et NO aux parents n'ayant pas envoyé la valeur minimum
                HashSet<String> no = new HashSet<>();
                for (String agent : this.incoming) {
                    if (!this.parents_ayant_valeur_min.contains(agent)) {
                        no.add(agent);
                    }
                }
                diffusionReponse(no, TypeMessage.NO.toString(), this.prune);
                no.clear();
            }
            inverse_node();


            //traacing
          tracer.log("UpOther", new Object[]{Integer.parseInt(id)});
        }

        if (etat == EtatNoeud.SOURCE) {
            while (!aRecuToutSesoutgoing) {
                attendreMessage();
            }
            inverse_node();

            //Tracing
            tracer.log("UpSource", new Object[]{Integer.parseInt(id)});
        }

        //parents ayant valeur min
        parents_ayant_valeur_min.clear();

        //apres avoir tout envoyé, on remet à false
        this.aRecuUnNO = false;
        aRecuToutSesoutgoing = false;
    }


    public void run() {
        mise_a_jour_etat();
        //demande au thread de s'arreter au bout de 0.5 seconde pour eviter les boucles infini
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            public void run() {
                System.exit(0); // Arrête le programme
            }
        }, 1000); // Démarre la tâche après 500 ms

        // transform incoming into list of integers
        List<Integer> incoming = this.incoming.stream().map(Integer::parseInt).toList();
        traceInGoing.addAll(incoming); /* ca ne marche pas car :Attempted to compare integer 2 with non-integer: <<"3">>*/

        // transform outgoing into list of integers
        List<Integer> outgoing = this.outgoing.stream().map(Integer::parseInt).toList();
        traceOutGoing.addAll(outgoing);


        while (isActive) {
            try {


                // tracer.log("AddInteger", new Object[]{incoming});/* ca ne marche pas car, car le path est un string alors que tla demande un Integer :Attempted to compare integer 2 with non-integer: <<"3">>*/
                // tracer.notifyChange("incoming", "AddElement", }, incoming);

                System.out.println("id: " + id + " mon etat: " + etat + " " + incoming + " " + outgoing + " mini: " + mini_actuel);


                phase_yo_down();
                phase_yo_up();
                mise_a_jour_etat();
                System.out.println();

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("je suis le noued " + id +" et j'ai finit" );

    }


    public void attendreMessage() throws IOException {
        Message message = null;
        try {
            message = networkManager.receive(id, 1000);
        } catch (TimeOutException e) {
            System.out.println("timeout");
            System.exit(1);
        }
        if (message != null) {
            this.handleMessage(message);
        } else {
            System.out.println("message est nul, beug");
        }
    }

    public void diffusionID(Set<String> destinataire, String idADiffuser, String prune) throws IOException {
        for (String agent : destinataire) {
            //transfere son id à chaque agent dans ses outgoing
            networkManager.send(new Message(this.id, agent, TypeMessage.ID.toString(), this.phase, idADiffuser, prune, temps.getNextTime()));
        }
        traceMessages.add(Map.of("phase", this.phase, "sndr", this.id,"val",this.mini_actuel));
        tracer.log();
    }

    public void diffusionReponse(Set<String> destinataire, String reponse, String prune) throws IOException {
        for (String agent : destinataire) {
            if (reponse.equals("NO")) {
                this.noeud_a_inverser.add(agent);
            }

            //transfere son id à chaque agent dans ses outgoing
            networkManager.send(new Message(this.id, agent, reponse, this.phase, "NoIdToSend", prune, temps.getNextTime()));
        }
        traceMessages.add(Map.of("phase", this.phase, "sndr", this.id,"reply",reponse));
        tracer.log();
    }

    private void handleMessage(Message message) throws IOException {

        //Verifie si le message nous appartient
        if (message != null && message.getTo().equals(id)) {

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

                    this.compteurMsgincoming++;
                    if (compteurMsgincoming == incoming.size()) {
                        compteurMsgincoming = 0;
                        this.aRecuToutSesincoming = true;
                    }
                }


                //cas ou on recoit un YES
                if (message.getType().equals(TypeMessage.YES.toString())) {
                    compteurMsgSortant++;
                    //on a recu tout les reponses des voisins outgoing
                    if (compteurMsgSortant == outgoing.size()) {
                        compteurMsgSortant = 0;
                        aRecuToutSesoutgoing = true;
                    }
                }

                //cas ou on recoit un NO
                if (message.getType().equals(TypeMessage.NO.toString())) {

                    //on le stocke
                    noeud_a_inverser.add(message.getFrom());
                    this.aRecuUnNO = true;
                    compteurMsgSortant++;
                }

                // Vérifier si on a reçu tous les messages outgoing
                if (compteurMsgSortant == outgoing.size()) {
                    compteurMsgSortant = 0;
                    aRecuToutSesoutgoing = true;
                }

            } else {
                //si c'est c'est un message en avance on stocke le pour la ronde suivante
                System.out.println("message en avance capture --> " + message + " alors que je suis en " + this.phase + " " + incoming + " " + outgoing);
                networkManager.send(message);
            }
        }
    }
}

