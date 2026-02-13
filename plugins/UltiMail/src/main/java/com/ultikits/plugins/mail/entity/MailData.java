package com.ultikits.plugins.mail.entity;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Mail data entity.
 * <p>
 * Stores mail information including sender, receiver, content, attachments,
 * and optional commands to execute when mail is read.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Table("mail_messages")
public class MailData extends BaseDataEntity<String> {
    
    @Column("sender_uuid")
    private String senderUuid;
    
    @Column("sender_name")
    private String senderName;
    
    @Column("receiver_uuid")
    private String receiverUuid;
    
    @Column("receiver_name")
    private String receiverName;
    
    @Column("subject")
    private String subject;
    
    @Column("content")
    private String content;
    
    @Column(value = "items", type = "TEXT")
    private String items; // Serialized ItemStack array in Base64
    
    /**
     * Commands to execute when mail is read.
     * Stored as JSON array: ["command1", "console:command2"]
     * Commands prefixed with "console:" will be executed by console.
     * Supports %player% placeholder.
     * <p>
     * This is an API-only feature for third-party plugins.
     */
    @Column(value = "commands", type = "TEXT")
    private String commands; // JSON array of commands
    
    @Column(value = "sent_time", type = "BIGINT")
    private long sentTime;
    
    @Column(value = "read_status", type = "BOOLEAN")
    private boolean read;
    
    @Column(value = "claimed_status", type = "BOOLEAN")
    private boolean claimed; // Items claimed
    
    /**
     * Whether commands have been executed.
     * Commands are executed only once when mail is first read.
     */
    @Column(value = "commands_executed", type = "BOOLEAN")
    private boolean commandsExecuted;
    
    @Column(value = "deleted_by_sender", type = "BOOLEAN")
    private boolean deletedBySender;
    
    @Column(value = "deleted_by_receiver", type = "BOOLEAN")
    private boolean deletedByReceiver;
    
    public MailData() {
        // Generate timestamp-based ID
        this.setId(String.valueOf(System.currentTimeMillis()));
        this.sentTime = System.currentTimeMillis();
        this.read = false;
        this.claimed = false;
        this.commandsExecuted = false;
        this.deletedBySender = false;
        this.deletedByReceiver = false;
    }
    
    /**
     * Check if this mail has attachments.
     */
    public boolean hasItems() {
        return items != null && !items.isEmpty();
    }
    
    /**
     * Check if this mail has commands to execute.
     */
    public boolean hasCommands() {
        return commands != null && !commands.isEmpty() && !commands.equals("[]");
    }
}
