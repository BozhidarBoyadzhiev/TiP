package dev.tip.devices.api;

import dev.tip.shared.DomainException;
import org.springframework.http.HttpStatus;

public class DeviceNotFoundException extends DomainException {

    public DeviceNotFoundException() {
        super(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "No device with this name exists.");
    }
}
