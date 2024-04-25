package org.lbee.protocol;

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
    private Set<String> entrants;
    private Set<String> sortants;
    private EtatNoeud etat;
    private boolean aRecuToutSesEntrants;
    private boolean aRecuToutSesSortants;
    private int compteurMsgEntrants;
    private int compteurMsgSortant;
    private boolean aRecuUnNO;
    private Set<String> parents_ayant_valeur_min;
    //le minimum actuel dans la phase descendante
    private String mini_actuel;
    private String phase;
    final NetworkManager networkManager;
    private InstrumentationClock temps;

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
        this.entrants = in;
        this.sortants = out;
        this.etat = EtatNoeud.INCONNU;
        this.aRecuToutSesEntrants = false;
        this.aRecuToutSesSortants = false;
        this.aRecuUnNO = false;
        this.noeud_a_inverser = new HashSet<>();
        this.compteurMsgEntrants = 0;
        this.compteurMsgSortant = 0;
        this.parents_ayant_valeur_min = new HashSet<>();
        this.mini_actuel = id;

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
        }
        if (!this.sortants.isEmpty() && !this.entrants.isEmpty()) {
            this.etat = EtatNoeud.INTERNE;
        }
    }

    //on inverse les nœuds à la fin de la phase -YO
    public void inverse_node() {
        for (String node : noeud_a_inverser) {
            //System.out.println("le noued "+ id+ " a inverser les noueds :" + noeud_a_inverser);
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


        if (this.etat == EtatNoeud.SOURCE) {
            diffusionID(this.sortants, this.id);
            tracer.log("DownSource", new Object[]{Integer.parseInt(id)});

        } else {
            //cas ou on est dans un noeud interne/puits, on attend d'avoir recu tout les id des noueds entrants
            while (!this.aRecuToutSesEntrants) {
                // Attendre que tous les messages soient reçus avant de transferer le min actuel
                attendreMessage();
            }
            diffusionID(this.sortants, mini_actuel);
            //tracing
            tracer.log("DownOther", new Object[]{Integer.parseInt(id)});
        }

        //mise a jour variable pour la seconde ronde
        aRecuToutSesEntrants = false;
    }

    public void phase_yo_up() throws IOException {
        phase = "up";
        // trace the down phase
        //cas ou le noeud actuel est un puit
        if (this.etat == EtatNoeud.PUITS) {
            //on envoie YES au entrants ayant envoyé val min
            diffusionReponse(this.parents_ayant_valeur_min, TypeMessage.YES.toString());

            //on cree la liste des gens qui ont envoyé No
            HashSet<String> noParent = new HashSet<>();
            for (String agent : this.entrants) {
                if (!this.parents_ayant_valeur_min.contains(agent)) {
                    noParent.add(agent);
                }
            }
            //On envoie NO au reste
            diffusionReponse(noParent, TypeMessage.NO.toString());
            noParent.clear();
            inverse_node();

            //tracing
            tracer.log("UpOther", new Object[]{Integer.parseInt(id)});

        }

        //cas ou nous somme dans un noued interne
        if (this.etat == EtatNoeud.INTERNE) {
            //on attends qu'ils recoit les messages de ses sortants
            while (!aRecuToutSesSortants) {
                attendreMessage();
            }

            //si on recoit un No des sortants, on le proapage dans les entrants
            if (this.aRecuUnNO) {
                diffusionReponse(this.entrants, TypeMessage.NO.toString());
                noeud_a_inverser.addAll(entrants);
            } else {

                //sinon on propage yes entrant ayant envoyé la val min
                diffusionReponse(this.parents_ayant_valeur_min, TypeMessage.YES.toString());

                //Et NO aux parents n'ayant pas envoyé la valeur minimum
                HashSet<String> no = new HashSet<>();
                for (String agent : this.entrants) {
                    if (!this.parents_ayant_valeur_min.contains(agent)) {
                        no.add(agent);
                    }
                }
                diffusionReponse(no, TypeMessage.NO.toString());
                no.clear();
            }
            inverse_node();


            //traacing
          tracer.log("UpOther", new Object[]{Integer.parseInt(id)});
        }

        if (etat == EtatNoeud.SOURCE) {
            while (!aRecuToutSesSortants) {
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
        aRecuToutSesSortants = false;
    }


    public void run() {
        mise_a_jour_etat();
        //demande au thread de s'arreter au bout de 0.5 seconde pour eviter les boucles infini
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            public void run() {
                System.exit(0); // Arrête le programme
            }
        }, 3000); // Démarre la tâche après 500 ms

        while (true) {
            try {

                traceInGoing.set(entrants); /* ca ne marche pas car :Attempted to compare integer 2 with non-integer: <<"3">>*/

               // tracer.log("addElement", new Object[]{entrants});/* ca ne marche pas car, car le path est un string alors que tla demande un Integer :Attempted to compare integer 2 with non-integer: <<"3">>*/

               // tracer.notifyChange("incoming", "AddElement", }, entrants);

                System.out.println("id: " + id + " mon etat: " + etat + " " + entrants + " " + sortants + " mini: " + mini_actuel);


                phase_yo_down();
                phase_yo_up();
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
            message = networkManager.receive(id, 1000);
        } catch (TimeOutException e) {
            //System.out.println("timeOut");
        }
        if (message != null) {
            this.handleMessage(message);
        } else {
            System.out.println("message est nul, beug");
        }
    }

    public void diffusionID(Set<String> destinataire, String idADiffuser) throws IOException {

        for (String agent : destinataire) {
            //transfere son id à chaque agent dans ses sortants
            networkManager.send(new Message(this.id, agent, TypeMessage.ID.toString(), this.phase, idADiffuser, temps.getNextTime()));
        }
    }

    public void diffusionReponse(Set<String> destinataire, String reponse) throws IOException {
        for (String agent : destinataire) {
            if (reponse.equals("NO")) {
                this.noeud_a_inverser.add(agent);
            }
            //transfere son id à chaque agent dans ses sortants
            networkManager.send(new Message(this.id, agent, reponse, this.phase, "NoIdToSend", temps.getNextTime()));
        }
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

                    this.compteurMsgEntrants++;
                    if (compteurMsgEntrants == entrants.size()) {
                        compteurMsgEntrants = 0;
                        this.aRecuToutSesEntrants = true;
                    }
                }


                //cas ou on recoit un YES
                if (message.getType().equals(TypeMessage.YES.toString())) {

                    /*if(id.equals("3")){
                        System.out.println("---->id: " + id + " mon etat: " + etat + " " + entrants + " " + sortants + " recu: " + message.getFrom());
                    }*/

                    compteurMsgSortant++;
                    //on a recu tout les reponses des voisins sortants
                    if (compteurMsgSortant == sortants.size()) {
                        compteurMsgSortant = 0;
                        aRecuToutSesSortants = true;
                    }
                }

                //cas ou on recoit un NO
                if (message.getType().equals(TypeMessage.NO.toString())) {

                    //on le stocke
                    noeud_a_inverser.add(message.getFrom());

                    this.aRecuUnNO = true;
                    this.compteurMsgSortant++;
                    if (compteurMsgSortant == sortants.size()) {
                        compteurMsgSortant = 0;
                        aRecuToutSesSortants = true;
                    }
                }
            } else {
                //si c'est c'est un message en avance on stocke le pour la ronde suivante
                System.out.println("message en avance capture --> " + message + " alors que je suis en " + this.phase + " " + entrants + " " + sortants);
                networkManager.send(message);
            }
        }
    }
}

