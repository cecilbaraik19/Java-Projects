package com.cecil.edge.model;

import com.cecil.edge.model.Enums.CommandStatus;
import com.cecil.edge.model.Enums.CommandType;

/** An instruction queued for an edge node. The node collects it on its next heartbeat. */
public class Command {
    public String id;
    public String nodeId;
    public CommandType type;
    public String arg;
    public CommandStatus status = CommandStatus.PENDING;
    public String by;
    public long createdAt;
    public long doneAt;
    public String result;
}
