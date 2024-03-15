package org.lbee.protocol;

import org.lbee.network.NetworkManager;
import org.lbee.network.TimeOutException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AgentYoyo {

    private String id;
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

    final NetworkManager networkManager;

    public AgentYoyo(NetworkManager networkManager, String id, Set<String> entrants, Set<String> sortants) {
        this.networkManager = networkManager;

        this.id = id;
        this.entrants = entrants;
        this.sortants = sortants;
        this.etat = EtatNoeud.INCONNU;
        this.aRecuToutSesEntrants = false;
        this.aRecuToutSesSortants = false;
        this.noeud_a_inverser = new HashSet<>();
        this.compteurMsgEntrants = 0;
        this.compteurMsgDeSortant = 0;
        this.parents_ayant_valeur_min = new HashSet<>();
        this.mini_actuel = id;
        mise_a_jour_etat();
    }

    public void ajouterEntrant(String agent){
        entrants.add(agent);
    }

    public void ajouterSortant(String agent){
        sortants.add(agent);
    }

    public String getId() {
        return id;
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

    //on Inverse les nœuds à la fin de la phase -YO
    public void inverse_node() {
        for (String node : this.noeud_a_inverser) {
            if (this.entrants.contains(node)) {
                this.entrants.remove(node);
                this.sortants.add(node);
            } else if (this.sortants.contains(node)) {
                this.sortants.remove(node);
                this.entrants.add(node);
            }
        }
    }

    public void attendreMessage() throws IOException {
        Message message = null;
        try {
            message = networkManager.receive(id,0);
        } catch (TimeOutException e) {
            System.out.println(id + " received TIMEOUT ");
        }
        if (message != null) {
            this.handleMessage(message);
        }
    }

    public void phase_yo_down() throws IOException {
        if (this.etat == EtatNoeud.SOURCE) {
            //les sources envoie leur id aux agents sortants
            this.aRecuToutSesEntrants = true;
            for (String agent : this.sortants) {
                //transfere son id a chaque agent dans ses sortant
                networkManager.send(new Message(this.id, agent, TypeMessage.ID.toString(), this.id, 0));
            }
        } else {
            //cas ou on est dans un noeud interne/puits, on attend d'avoir recu tout les id des agents rentrant
            while (!this.aRecuToutSesEntrants) {
                // Attendre que tous les messages soient reçus
                attendreMessage();
            }
            //les internes envoie leur id a leur sortant
            for (String agent : this.sortants) {
                networkManager.send(new Message(this.id, agent, TypeMessage.ID.toString(), mini_actuel, 0));
            }

        }

        this.aRecuToutSesEntrants = true;
        this.compteurMsgEntrants = 0;
    }

    public void phase_yo_up() throws IOException {

        // Attendre que tous les messages des entrants soient reçus
        while (!this.aRecuToutSesEntrants) {
            attendreMessage();
        }

        //cas ou nous somme un puit
        if (this.etat == EtatNoeud.PUITS) {
            //envoyer YES a tous les puits ayant des parents avec l'id minimum
            for (String agent : this.parents_ayant_valeur_min) {
                networkManager.send(new Message(this.id, agent, TypeMessage.YES.toString(), id, 0));
            }

            //On envoie NO au reste
            for (String agent : this.entrants) {
                if (!this.parents_ayant_valeur_min.contains(agent)) {
                    networkManager.send(new Message(this.id, agent, TypeMessage.NO.toString(), id, 0));
                    this.noeud_a_inverser.add(agent);
                }
            }
            this.aRecuToutSesSortants = true;
        }

        //cas ou nous somme dans un noued interne
        if ( this.etat == EtatNoeud.INTERNE) {
            //si on recoit un No des sortant, on le proapage dans les entrants
            if (this.aRecuNO) {
                for (String agent : this.entrants) {
                    networkManager.send(new Message(this.id, agent, TypeMessage.NO.toString(), id, 0));
                    this.noeud_a_inverser.add(agent);
                }
            } else {
                for (String agent : this.parents_ayant_valeur_min) {
                    networkManager.send(new Message(this.id, agent, TypeMessage.YES.toString(), id, 0));
                }
                for (String agent : this.entrants) {
                    if (!this.parents_ayant_valeur_min.contains(agent)) {
                        networkManager.send(new Message(this.id, agent, TypeMessage.NO.toString(), id, 0));
                    }
                    this.noeud_a_inverser.add(agent);
                }
            }
        }

        if (etat == EtatNoeud.SOURCE) {
            while (!this.aRecuToutSesSortants) {
                // on attends de recevoir tout les noeds sortant
                attendreMessage();
            }
        }

        this.aRecuNO = false;
        this.compteurMsgDeSortant = 0;
    }


    public void run() {
        System.out.println("je suis le noeud: " + id + " voici mes entrants: "+ entrants+  "et mes sortants: "+sortants);
        while (true) {
            try {
                phase_yo_down();

                phase_yo_up();
                inverse_node();
                mise_a_jour_etat();

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }


    private void handleMessage(Message message) throws IOException {

        //Verifie si le message nous appartient
        if (message.getTo().equals(id)){

            //cas ou on recoit un ID
            if (message.getType().equals(TypeMessage.ID.toString()) ) {

                //si on a le meme id, on le rajoute dans l'ensemble des parents ayant la valeur min
                if (Integer.parseInt(message.getContent()) == Integer.parseInt(mini_actuel)) {
                    parents_ayant_valeur_min.add(message.getContent());
                }
                //si la valeur recu est plus petite, on efface la liste et on le rajoute
                if (Integer.parseInt(message.getContent()) < Integer.parseInt(mini_actuel)) {
                    parents_ayant_valeur_min.clear();
                    parents_ayant_valeur_min.add(message.getContent());
                }

                this.compteurMsgEntrants++;
                if (compteurMsgEntrants == entrants.size()) {
                    this.aRecuToutSesEntrants = true;
                }

                //cas ou on recoit un YES
                if (message.getType().equals(TypeMessage.YES.toString())) {
                    compteurMsgDeSortant++;
                    //on a recu tout les reponses des voisins sortants
                    if (compteurMsgDeSortant == sortants.size()) {
                        aRecuToutSesSortants = true;
                    }

                }

                //cas ou on recoit un NO
                if (message.getType().equals(TypeMessage.NO.toString())) {
                    noeud_a_inverser.add(message.getFrom());
                    this.aRecuNO = true;
                    this.compteurMsgDeSortant++;
                    if (compteurMsgDeSortant == sortants.size()) {
                        aRecuToutSesSortants = true;
                    }
                }
            }
        }
    }
}
