package com.cecil.sangam.model;

/**
 * What a person may do.
 *  ADMIN  – the workspace owner: creates projects, adds people, sees the activity log.
 *  MEMBER – a normal team member.
 *  GUEST  – somebody from outside (a client): sees only the projects they are added to, and cannot move tasks.
 */
public enum Role { ADMIN, MEMBER, GUEST }
