public enum MailProtocolType {
    SMTP("SMTP"), 
    IMAP("IMAP"), 
    POP3("POP3");

    private final String name;

    private MailProtocolType (String name) {
        this.name = name;
    }

    /**
     * Get the name of the mail protocol.
     * @return
     */
    public String getName() {
        return name;
    }

    /** 
     * Get the MailProtocol enum from a string name.
     * @param name
     * @return
    */
    @Override
    public String toString() {
        return getName();
    }

    /**
     * Check if the name of this protocol equals another protocol's name, ignoring case.
     * @param otherName
     * @return
    */
    public boolean equalsName(MailProtocolType otherName) {
        return this.name.equalsIgnoreCase(otherName.toString());
    }
}
