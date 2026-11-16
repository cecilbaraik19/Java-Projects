package com.cecil.dainandini.model;

/** TODO = not started, DOING = started, DONE = finished, DROPPED = decided not to do it. */
public enum TaskStatus {
    TODO, DOING, DONE, DROPPED;

    public boolean open() { return this == TODO || this == DOING; }
}
