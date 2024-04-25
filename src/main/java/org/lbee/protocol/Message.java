package org.lbee.protocol;

/**
 * Message
 */
public class Message {

    private final String from;
    private final String content;
    private final long senderClock;
    private final String to;

    private final String type;

    private final String phase;
    private  final String prune;

    public Message(String from, String to,  String type, String phase, String content,String prune,long senderClock) {
        this.from = from;
        this.to = to;
        this.type = type;
        this.phase = phase;
        this.content = content;
        this.prune = prune;
        this.senderClock = senderClock;
    }

    /**
     * Create message from 4 strings (doesn't check length, so it can throw an out of bound exception)
     *
     * @param components Strings used to construct the message
     */
    public Message(String[] components) {
        this.from = components[0];
        this.to = components[1];
        this.type = components[2];
        this.phase = components[3];
        this.content = components[4];
        this.prune = components[5];
        this.senderClock = Long.parseLong(components[6]);
    }

    public String getFrom() {
        return from;
    }

    public String getContent() {
        return content;
    }

    public String getTo() {
        return to;
    }

    public long getSenderClock() {
        return senderClock;
    }

    public Boolean getPrune() {
        return prune.equals("true");
    }

    public String getType() {
        return type;
    }

    public String getPhase() {
        return phase;
    }

    @Override
    public String toString() {
        return String.join(";", new String[]{from, to, type,phase,content,prune, Long.toString(senderClock)});
    }
}
