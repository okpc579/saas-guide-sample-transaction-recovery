package com.example.transactionrecovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileConfigurationTest {

    @Test
    void bindsProfileConfiguration() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "profile.name", "local"
        )));

        ProfileConfiguration configuration = binder
                .bind("profile", Bindable.of(ProfileConfiguration.class))
                .orElseThrow(() -> new IllegalStateException("profile configuration was not bound"));

        assertThat(configuration.name()).isEqualTo("local");
    }

    private record ProfileConfiguration(String name) {
    }
}
