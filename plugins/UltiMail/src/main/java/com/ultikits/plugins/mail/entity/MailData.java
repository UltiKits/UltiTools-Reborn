package com.ultikits.plugins.mail.entity;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * Mail data entity.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Table("mail_messages")
public class MailData extends AbstractDataEntity {
    
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
    
    @Column(value = "sent_time", type = "BIGINT")
    private long sentTime;
    
    @Column(value = "read_status", type = "BOOLEAN")
    private boolean read;
    
    @Column(value = "claimed_status", type = "BOOLEAN")
    private boolean claimed; // Items claimed
    
    @Column(value = "deleted_by_sender", type = "BOOLEAN")
    private boolean deletedBySender;
    
    @Column(value = "deleted_by_receiver", type = "BOOLEAN")
    private boolean deletedByReceiver;
    
    public MailData() {
        this.sentTime = System.currentTimeMillis();
        this.read = false;
        this.claimed = false;
        this.deletedBySender = false;
        this.deletedByReceiver = false;
    }
}
