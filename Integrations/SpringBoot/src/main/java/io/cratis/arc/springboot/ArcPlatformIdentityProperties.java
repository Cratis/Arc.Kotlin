// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit opt-in for unsigned platform headers behind an authenticated, isolated ingress. */
@ConfigurationProperties("cratis.arc.platform-identity")
public final class ArcPlatformIdentityProperties {
    private boolean enabled;

    /** Gets whether the platform identity bridge is enabled; defaults to false. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Enables the bridge, which still requires an application trusted-ingress policy. */
    public void setEnabled(boolean value) {
        enabled = value;
    }
}
