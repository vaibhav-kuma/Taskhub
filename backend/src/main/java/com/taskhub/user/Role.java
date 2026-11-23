package com.taskhub.user;

public enum Role {
    /** Full access to every project and to user administration. */
    ADMIN,
    /** Can create projects and manage the ones they own. */
    MANAGER,
    /** Works on projects they own or were invited to. */
    MEMBER
}
