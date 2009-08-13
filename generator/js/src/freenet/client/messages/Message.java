package freenet.client.messages;

/** This class represents a message that can be shown in the message panel of the page */
public class Message {

	/** The text of the message */
	private String		msg;

	/** The priority of the message */
	private Priority	priority;

	/** The anchor of the message, if originated from server side, or null, if client side */
	private String		anchor;

	public Message(String msg, Priority priority, String anchor) {
		super();
		this.msg = msg;
		this.priority = priority;
		this.anchor = anchor;
	}

	public String getMsg() {
		return msg;
	}

	public Priority getPriority() {
		return priority;
	}

	public String getAnchor() {
		return anchor;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Message) {
			Message message = (Message) obj;
			return msg.compareTo(message.msg) == 0 && priority == message.priority && ((anchor == null && message.anchor == null) || anchor != null && message.anchor != null && anchor.compareTo(message.anchor) == 0);
		} else {
			return false;
		}
	}
}