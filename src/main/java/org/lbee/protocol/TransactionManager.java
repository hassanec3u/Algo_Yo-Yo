package org.lbee.protocol;

import org.lbee.instrumentation.VirtualField;
import org.lbee.network.NetworkManager;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
// import java.util.stream.Collectors;

public class TransactionManager extends Manager {

    // Config
    private final TransactionManagerConfiguration config;
    // Resource manager linked to TM
    private final HashSet<String> resourceManagers;

    // Number of resource manager prepared to commit
    private int nbPrepared;

    // private boolean isAllRegistered = false;

    private final VirtualField specTmPrepared;

    public TransactionManager(NetworkManager networkManager, TransactionManagerConfiguration config)
            throws IOException {
        super("TM", networkManager);

        resourceManagers = new HashSet<>();
        // Note: invert comment to introduce bug
        // nbPrepared = 0;
        // Even if nbPrepared is false, increase the commit duration led to a valid
        // trace
        // Because the last RM have time to send is Prepared message before TM propose
        // to commit
        nbPrepared = 0;

        this.config = config;

        this.specTmPrepared = spec.getVariable("tmPrepared");
    }

    @Override
    public void run() throws IOException {
        do {
            // Check eventual received message
            Message message = networkManager.receive(this.getName());
            if (message == null) {
                continue;
            }
            this.receive(message);

            // Waiting for all resource manager registered
            if (resourceManagers.size() < config.nResourceManager()) {
                continue;
            }

            if (checkCommit()) {
                this.commit();
                System.out.println("TM COMMIT");
            }
        } while (!this.isShutdown());
    }

    protected void receive(Message message) throws IOException {
        if (message.getContent().equals(TwoPhaseMessage.Register.toString())) {
            this.receivedRegister(message.getFrom());
            System.out.println("tm REGISTER");
        } else if (message.getContent().equals(TwoPhaseMessage.Prepared.toString())) {
            this.receivePrepared(message.getFrom());
            System.out.println("tm PREPARED");
        } else {
            System.out.println("tm OTHER");
        }
    }

    protected void receivedRegister(String resourceManagerName) {
        System.out.printf("Register a new resource manager: %s.\n", resourceManagerName);
        this.resourceManagers.add(resourceManagerName);
    }

    protected boolean checkCommit() {
        return this.nbPrepared >= this.resourceManagers.size();
    }

    /**
     * @TLAAction TMCommit
     */
    private void commit() throws IOException {
        // Notify
        specMessages.add(Map.of("type", TwoPhaseMessage.Commit.toString()));
        spec.commitChanges("TMCommit");

        for (String rmName : resourceManagers)
            this.networkManager.send(new Message(this.getName(), rmName, TwoPhaseMessage.Commit.toString(), 0));

        // Display message
        System.out.println(TwoPhaseMessage.Commit + ".");

        // Shutdown
        this.shutdown();
    }

    /**
     * @TLAAction TMRcvPrepared(r)
     */
    public void receivePrepared(String sender) throws IOException {
        /* Search receive prepared resource manager in resource manager set */
        Optional<String> optionalResourceManager = resourceManagers.stream().filter(rmName -> rmName.equals(sender))
                .findFirst();
        /* If it doesn't exist, do nothing */
        if (optionalResourceManager.isEmpty())
            return;

        /* Add prepared resource manager to prepared set */
        String rmName = optionalResourceManager.get();
        nbPrepared++;
        specTmPrepared.add(rmName);
        spec.commitChanges("TMRcvPrepared");
    }

}
