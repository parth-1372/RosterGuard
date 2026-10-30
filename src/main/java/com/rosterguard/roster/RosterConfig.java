package com.rosterguard.roster;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RosterProperties.class)
public class RosterConfig {
}

