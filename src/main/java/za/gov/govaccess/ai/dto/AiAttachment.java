package za.gov.govaccess.ai.dto;

import java.util.Objects;

public record AiAttachment(byte[] bytes, String mimeType) {
    public AiAttachment {
        bytes = bytes.clone();
        Objects.requireNonNull(mimeType);
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
