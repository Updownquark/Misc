package org.qommons.misc.springdemo.service;

import lombok.NonNull;

public record AuthenticatedUser(@NonNull String id, @NonNull String userName, String fullName, String email) {
}
