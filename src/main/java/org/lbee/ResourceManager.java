package org.lbee;

import org.lbee.helpers.Helper;
import org.lbee.instrumentation.VirtualField;
import org.lbee.models.Message;
import org.lbee.models.TwoPhaseMessage;

import java.io.IOException;
import java.net.Socket;
import java.util.Locale;
import java.util.Map;

public class ResourceManager extends Manager implements NamedClient {

    /**
     * Possible states of resource manager
     */
    enum ResourceManagerState {
        WORKING,
        PREPARED,
        COMMITTED
    }

    // Config
    private final ResourceManagerConfiguration config;
    // Transaction manager (to send message)
    private final String transactionManagerName;

    // Current state
    private ResourceManagerState state = ResourceManagerState.WORKING;

    private final VirtualField specState;

    /**
     * Construct a resource manager
     * @param socket Client socket
     * @param name Resource manager name
     * @param transactionManagerName Attached transaction manager name
     * @param config Resource manager config
     * @throws IOException Throw when errors occur on socket
     */
    public ResourceManager(Socket socket, String name, String transactionManagerName, ResourceManagerConfiguration config) throws IOException {
        super(name, socket);
        this.config = config;
        this.transactionManagerName = transactionManagerName;
        specState = spec.getVariable("rmState").getField(getName());
    }

    private void reset() throws IOException {
        setState(ResourceManagerState.WORKING);
        spec.commitChanges("RMReset");
    }

    /**
     * Set state of manager
     * @param state New manager state
     */
    private void setState(ResourceManagerState state) {
        // Change state
        this.state = state;
        specState.set(state.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * Get state of manager
     * @return Current state of manager
     */
    public ResourceManagerState getState() {
        return this.state;
    }

    @Override
    public void run() throws IOException {
        // Check eventual received message
        super.run();

        // If working simulate task, and then prepare
        if (this.getState() == ResourceManagerState.WORKING) {
            try {
                Thread.sleep(config.taskDuration());
            } catch (InterruptedException ex) {
            }
            this.prepare();
        }

        // Continuously send prepared while not committed
        sendPrepared();

        /* Task fail eventually */
        if (this.config.shouldFail())
            throw new IOException();
    }

    @Override
    protected void receive(Message message) throws IOException {
        /* Eventually commit */
        if (message.getContent().equals(TwoPhaseMessage.Commit.toString()))
            this.commit();
        /* Nothing else to do */
    }

    protected void register() throws IOException {
        System.out.println("Registering...");
        this.networkManager.send(new Message(this.getName(), transactionManagerName, TwoPhaseMessage.Register.toString(), 0));
    }

    /**
     * @TLA-action RMPrepare(r)
     */
    protected void prepare() throws IOException {
        this.setState(ResourceManagerState.PREPARED);
    }

    private long lastSendTime;
    private void sendPrepared() throws IOException {
        // Compute elapsed time between now and last message send
        long elapsedTime = System.currentTimeMillis() - lastSendTime;
        // Send every second
        if (this.state == ResourceManagerState.PREPARED && elapsedTime >= 100) {
            specMessages.add(Map.of("type",TwoPhaseMessage.Prepared.toString(), "rm", getName()));
            spec.commitChanges();
            this.networkManager.send(new Message(this.getName(), transactionManagerName, TwoPhaseMessage.Prepared.toString(), 0));
            lastSendTime = System.currentTimeMillis();
        }
    }

    /**
     * @TLA-action RMRcvCommitMsg(r)
     */
    protected void commit() throws IOException {
        // Simulate some task that take some time
        long d = 150 + Helper.next(1000);
        //System.out.printf("COMMIT TASK DURATION of %s : %s ms.\n", this.getName(), d);
        try {Thread.sleep(d); } catch (InterruptedException ex) {}
        this.setState(ResourceManagerState.COMMITTED);
        spec.commitChanges("RMRcvCommitMsg");
        // Shutdown process
        this.shutdown();
    }

    /**
     * Check whether the manager is in working state
     * @return True if manager is in working state, else false
     */
    private boolean isWorking() {
        return this.getState() == ResourceManagerState.WORKING;
    }

    /**
     * Check whether the manager is in committed state
     * @return True if manager is in committed state, else false
     */
    private boolean isCommitted() {
        return this.getState() == ResourceManagerState.COMMITTED;
    }

}
