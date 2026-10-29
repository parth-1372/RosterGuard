package com.rosterguard.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rosterguard.security")
public record SecurityProperties(String jwtSecret, @DefaultValue("rosterguard") String issuer) {
}

