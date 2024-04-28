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

/**
 * Cette classe représente un agent Yoyo utilisé dans un système distribué pour la résolution
 * de problèmes de consensus.
 * <p>
 * L'agent Yoyo est conçu pour fonctionner dans un réseau distribué où chaque agent communique
 * avec ses voisins pour atteindre un consensus sur une valeur minimale.
 * <p>
 * L'agent Yoyo suit un algorithme basé sur les phases "down" et "up" pour atteindre le consensus.
 * Pendant la phase "down", les agents échangent des identifiants (IDs) avec leurs voisins sortants.
 * Pendant la phase "up", les agents diffusent des réponses à leurs voisins entrants en fonction
 * des informations reçues lors de la phase "down".
 * <p>
 * Cette classe gère la logique de fonctionnement d'un agent Yoyo, y compris la gestion des messages,
 * la mise à jour de l'état de l'agent, et la diffusion des informations aux voisins.
 * <p>
 * Les instances de cette classe doivent être créées avec un gestionnaire de réseau (NetworkManager),
 * un identifiant unique, un ensemble de voisins entrants (incoming) et un ensemble de voisins sortants (outgoing).
 * De plus, un traceur (TLATracer) est utilisé pour la surveillance et le débogage des interactions entre les agents.
 */
public class AgentYoyo {

    private String id;
    //pour stocker les messages recu de la ronde suivante
    private Set<String> incoming;
    private Set<String> outgoing;
    private EtatNoeud state;
    private boolean receivedAll_Incoming;
    private boolean receivedAll_Outgoing;
    private int incomingMessageCounter;
    private int outgoingMessageCounter;
    private boolean receiveNo;
    private Set<String> parentsWithMinValue;
    private String curentMin;
    private String phase;
    final NetworkManager networkManager;
    private InstrumentationClock time;
    private boolean isActive;
    private String prune;

    //ajoute nbIteration max pour eviter les boucles infini
    private int nbIterationMax = 100;


    //on stocke les noeuds a inversé
    private Set<String> noeud_a_inverser;

    // tracing
    private final TLATracer tracer;
    private final VirtualField traceMessages;
    private final VirtualField traceInGoing;
    private final VirtualField traceOutGoing;
    private final VirtualField tracePhase;

    /**
     * Constructeur de la classe AgentYoyo.
     *
     * @param networkManager Gestionnaire de réseau utilisé pour la communication avec les autres agents.
     * @param id             Identifiant unique de l'agent.
     * @param in             Ensemble des identifiants des voisins entrants.
     * @param out            Ensemble des identifiants des voisins sortants.
     * @param tracer         Traceur utilisé pour la surveillance et le débogage des interactions entre les agents.
     */
    public AgentYoyo(NetworkManager networkManager, String id, Set<String> in, Set<String> out, TLATracer tracer) {
        this.networkManager = networkManager;
        this.id = id;
        this.incoming = in;
        this.outgoing = out;
        this.state = EtatNoeud.INCONNU;
        this.receivedAll_Incoming = false;
        this.receivedAll_Outgoing = false;
        this.receiveNo = false;
        this.noeud_a_inverser = new HashSet<>();
        this.incomingMessageCounter = 0;
        this.outgoingMessageCounter = 0;
        this.parentsWithMinValue = new HashSet<>();
        this.curentMin = id;
        this.isActive = true;
        this.prune = "false";


        try {
            time = ClockFactory.getClock(2, "clock");
        } catch (ClockException e) {
            throw new RuntimeException(e);
        }

        this.traceMessages = tracer.getVariableTracer("mailbox").getField(Integer.parseInt(this.id));
        this.traceInGoing = tracer.getVariableTracer("incoming").getField(Integer.parseInt(this.id));
        this.traceOutGoing = tracer.getVariableTracer("outgoing").getField(Integer.parseInt(this.id));
        this.tracePhase = tracer.getVariableTracer("phase").getField(Integer.parseInt(this.id));
        this.tracer = tracer;
    }

    /**
     * Ajoute un identifiant à l'ensemble des voisins entrants.
     *
     * @param agent Identifiant de l'agent voisin à ajouter.
     */
    public void ajouterEntrant(String agent) {
        if (!agent.isEmpty()) {
            incoming.add(agent);
        }
    }

    /**
     * Ajoute un identifiant à l'ensemble des voisins sortants.
     *
     * @param agent Identifiant de l'agent voisin à ajouter.
     */
    public void ajouterSortant(String agent) {
        if (!agent.isEmpty()) {
            outgoing.add(agent);
        }
    }


    /**
     * Met à jour l'état de l'agent en fonction de ses voisins entrants et sortants.
     */
    public void mise_a_jour_state() {
        if (this.incoming.isEmpty()) {
            this.state = EtatNoeud.SOURCE;
        }
        if (this.outgoing.isEmpty()) {
            this.state = EtatNoeud.PUITS;
        }
        if (!this.outgoing.isEmpty() && !this.incoming.isEmpty()) {
            this.state = EtatNoeud.INTERNE;
        }
    }


    /**
     * Inverse les nœuds dans lesquels l'agent est connecté.
     */
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

    /**
     * Effectue la phase "down" de l'algorithme Yoyo.
     * Pendant cette phase, l'agent diffuse son identifiant à ses voisins sortants.
     *
     * @throws IOException En cas d'erreur de communication réseau.
     */
    public void phase_yo_down() throws IOException {
        phase = "down";

        if (this.state == EtatNoeud.SOURCE) {
            diffuseId(this.outgoing, this.id, this.prune);
            tracer.log("DownSource", new Object[]{Integer.parseInt(id)});

        } else {
            //cas ou on est dans un noeud interne/puits, on attend d'avoir recu tout les id des noueds incoming
            while (!this.receivedAll_Incoming) {
                // Attendre que tous les messages soient reçus avant de transferer le min actuel
                attendreMessage();
            }
            diffuseId(this.outgoing, curentMin, this.prune);
            //tracing
            tracer.log("DownOther", new Object[]{Integer.parseInt(id)});

        }

        //mise a jour variable pour la seconde ronde
        receivedAll_Incoming = false;
    }

    /**
     * Effectue la phase "up" de l'algorithme Yoyo.
     * Pendant cette phase, l'agent traite les réponses des voisins entrants et diffuse ses réponses
     * en fonction de l'état et des informations reçues lors de la phase "down".
     *
     * @throws IOException En cas d'erreur de communication réseau.
     */
    public void phase_yo_up() throws IOException {
        phase = "up";

        //cas ou le noeud actuel est un puit
        if (this.state == EtatNoeud.PUITS) {
            //on envoie YES au incoming ayant envoyé val min
            diffuseResponse(this.parentsWithMinValue, TypeMessage.YES.toString(), this.prune);

            //on cree la liste des gens qui ont envoyé No
            HashSet<String> noParent = new HashSet<>();
            for (String agent : this.incoming) {
                if (!this.parentsWithMinValue.contains(agent)) {
                    noParent.add(agent);
                }
            }
            //On envoie NO au reste
            diffuseResponse(noParent, TypeMessage.NO.toString(), this.prune);
            noParent.clear();
            inverse_node();

            //tracing
            tracer.log("UpOther", new Object[]{Integer.parseInt(id)});

        }

        //cas ou nous somme dans un noued interne
        if (this.state == EtatNoeud.INTERNE) {
            //on attends qu'ils recoit les messages de ses outgoing
            while (!receivedAll_Outgoing) {
                attendreMessage();
            }

            //si on recoit un No des outgoing, on le proapage dans les incoming
            if (this.receiveNo) {
                diffuseResponse(this.incoming, TypeMessage.NO.toString(), this.prune);
                noeud_a_inverser.addAll(incoming);
            } else {

                //sinon on propage yes entrant ayant envoyé la val min
                diffuseResponse(this.parentsWithMinValue, TypeMessage.YES.toString(), this.prune);

                //Et NO aux parents n'ayant pas envoyé la valeur minimum
                HashSet<String> no = new HashSet<>();
                for (String agent : this.incoming) {
                    if (!this.parentsWithMinValue.contains(agent)) {
                        no.add(agent);
                    }
                }
                diffuseResponse(no, TypeMessage.NO.toString(), this.prune);
                no.clear();
            }
            inverse_node();
            //tracing
            tracer.log("UpOther", new Object[]{Integer.parseInt(id)});
        }

        if (state == EtatNoeud.SOURCE) {
            while (!receivedAll_Outgoing) {
                attendreMessage();
            }
            inverse_node();
            //Tracing
            tracer.log("UpSource", new Object[]{Integer.parseInt(id)});
        }

        //parents ayant valeur min
        parentsWithMinValue.clear();

        //apres avoir tout envoyé, on remet à false
        this.receiveNo = false;
        receivedAll_Outgoing = false;
    }

    /**
     * Exécute l'agent Yoyo, en itérant sur les phases "down" et "up" jusqu'à ce qu'un consensus soit atteint
     * ou jusqu'à ce qu'un nombre maximal d'itérations soit atteint.
     */
    public void run() {
        mise_a_jour_state();
        // transform incoming into list of integers
        List<Integer> incoming = this.incoming.stream().map(Integer::parseInt).toList();
        traceInGoing.addAll(incoming);

        // transform outgoing into list of integers
        List<Integer> outgoing = this.outgoing.stream().map(Integer::parseInt).toList();
        traceOutGoing.addAll(outgoing);

        while (nbIterationMax > 0 && isActive) {
            try {

                System.out.println("id: " + id + " mon state: " + state + " " + incoming + " " + outgoing + " mini: " + curentMin);

                phase_yo_down();
                phase_yo_up();
                mise_a_jour_state();
                System.out.println();

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            nbIterationMax--;

        }
        System.out.println("je suis le noued " + id + " et j'ai finit");

    }

    /**
     * Attend la réception d'un message provenant d'un voisin.
     *
     * @throws IOException En cas d'erreur de communication réseau.
     */
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

    /**
     * Diffuse l'identifiant spécifié aux destinataires fournis.
     *
     * @param destinataire Ensemble des identifiants des destinataires.
     * @param idADiffuser  Identifiant à diffuser.
     * @param prune        Valeur de prune à inclure dans le message.
     * @throws IOException En cas d'erreur de communication réseau.
     */
    public void diffuseId(Set<String> destinataire, String idADiffuser, String prune) throws IOException {
        for (String agent : destinataire) {
            //transfere son id à chaque agent dans ses outgoing
            networkManager.send(new Message(this.id, agent, TypeMessage.ID.toString(), this.phase, idADiffuser, prune, time.getNextTime()));
        }
        traceMessages.add(Map.of("phase", this.phase, "sndr", Integer.parseInt(this.id), "val", Integer.parseInt(this.curentMin)));

    }

    /**
     * Diffuse la réponse spécifiée aux destinataires fournis.
     *
     * @param destinataire Ensemble des identifiants des destinataires.
     * @param reponse      Réponse à diffuser ("YES" ou "NO").
     * @param prune        Valeur de prune à inclure dans le message.
     * @throws IOException En cas d'erreur de communication réseau.
     */
    public void diffuseResponse(Set<String> destinataire, String reponse, String prune) throws IOException {
        for (String agent : destinataire) {
            if (reponse.equals("NO")) {
                this.noeud_a_inverser.add(agent);
            }
            //transfere son id à chaque agent dans ses outgoing
            networkManager.send(new Message(this.id, agent, reponse, this.phase, "NoIdToSend", prune, time.getNextTime()));
        }
        traceMessages.add(Map.of("phase", this.phase, "sndr", Integer.parseInt(this.id), "reply", reponse));

    }

    /**
     * Gère la réception d'un message par l'agent.
     *
     * @param message Message reçu.
     * @throws IOException En cas d'erreur de communication réseau.
     */
    private void handleMessage(Message message) throws IOException {

        //Verifie si le message nous appartient
        if (message != null && message.getTo().equals(id)) {

            if (message.getPhase().equals(this.phase)) {

                System.out.println("\u001B[32m   Message received: " + message + "\u001B[0m");

                //cas ou on recoit un ID
                if (message.getType().equals(TypeMessage.ID.toString())) {
                    //si on a le meme id, on le rajoute dans l'ensemble des parents ayant la valeur min
                    if (Integer.parseInt(message.getContent()) == Integer.parseInt(curentMin)) {
                        parentsWithMinValue.add(message.getFrom());
                    }
                    //si la valeur recu est plus petite, on efface la liste et on le rajoute
                    if (Integer.parseInt(message.getContent()) < Integer.parseInt(curentMin)) {
                        parentsWithMinValue.clear();
                        parentsWithMinValue.add(message.getFrom());
                        curentMin = message.getContent();
                    }
                    this.incomingMessageCounter++;
                    if (incomingMessageCounter == incoming.size()) {
                        incomingMessageCounter = 0;
                        this.receivedAll_Incoming = true;
                    }
                }
                //cas ou on recoit un YES
                if (message.getType().equals(TypeMessage.YES.toString())) {
                    outgoingMessageCounter++;
                    //on a recu tout les reponses des voisins outgoing
                    if (outgoingMessageCounter == outgoing.size()) {
                        outgoingMessageCounter = 0;
                        receivedAll_Outgoing = true;
                    }
                }
                //cas ou on recoit un NO
                if (message.getType().equals(TypeMessage.NO.toString())) {

                    //on le stocke
                    noeud_a_inverser.add(message.getFrom());
                    this.receiveNo = true;
                    outgoingMessageCounter++;
                }
                // Vérifier si on a reçu tous les messages outgoing
                if (outgoingMessageCounter == outgoing.size()) {
                    outgoingMessageCounter = 0;
                    receivedAll_Outgoing = true;
                }

            } else {
                //si c'est c'est un message en avance on stocke le pour la ronde suivante
                System.out.println("message en avance capture --> " + message + " alors que je suis en " + this.phase + " " + incoming + " " + outgoing);
                networkManager.send(message);
            }
        }
    }
}

