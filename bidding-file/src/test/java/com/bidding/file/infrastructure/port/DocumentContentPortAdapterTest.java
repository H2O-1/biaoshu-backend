package com.bidding.file.infrastructure.port;

import com.bidding.common.port.DocumentContentPort;
import com.bidding.file.domain.model.Document;
import com.bidding.file.domain.port.DocumentRepository;
import com.bidding.file.domain.port.ObjectStoragePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentContentPortAdapterTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ObjectStoragePort objectStoragePort;

    @InjectMocks
    private DocumentContentPortAdapter adapter;

    @Test
    void openActiveReturnsStreamForActiveDocument() throws Exception {
        Document doc = new Document(
                9L, 2L, 100L, 50L,
                "a.pdf", "application/pdf", "pdf", "招标正文", 4L,
                "org/2/package/50/a.pdf",
                "PENDING", "ACTIVE", 10L,
                Instant.parse("2026-07-27T06:00:00Z"),
                Instant.parse("2026-07-27T06:00:00Z")
        );
        when(documentRepository.findById(9L)).thenReturn(Optional.of(doc));
        when(objectStoragePort.open("org/2/package/50/a.pdf"))
                .thenReturn(new ByteArrayInputStream("%PDF".getBytes(StandardCharsets.UTF_8)));

        DocumentContentPort.DocumentContent content = adapter.openActive(9L);

        assertNotNull(content);
        assertEquals(9L, content.getDocumentId());
        assertEquals(2L, content.getOrgId());
        assertEquals(100L, content.getProjectId());
        assertEquals(50L, content.getPackageId());
        assertEquals("a.pdf", content.getFileName());
        assertEquals("pdf", content.getFileExt());
        assertEquals(4L, content.getSizeBytes());
        assertEquals("org/2/package/50/a.pdf", content.getObjectKey());

        InputStream in = content.getInputStream();
        assertNotNull(in);
        byte[] buf = new byte[4];
        assertEquals(4, in.read(buf));
        assertEquals("%PDF", new String(buf, StandardCharsets.UTF_8));
        in.close();

        verify(objectStoragePort).open("org/2/package/50/a.pdf");
    }

    @Test
    void openActiveReturnsNullWhenMissing() {
        when(documentRepository.findById(9L)).thenReturn(Optional.empty());

        assertNull(adapter.openActive(9L));
        verifyNoInteractions(objectStoragePort);
    }

    @Test
    void openActiveReturnsNullWhenDeleted() {
        Document doc = new Document(
                9L, 2L, null, 50L,
                "a.pdf", "application/pdf", "pdf", "招标正文", 1L,
                "k", "PENDING", "DELETED", 10L,
                Instant.parse("2026-07-27T06:00:00Z"),
                Instant.parse("2026-07-27T06:00:00Z")
        );
        when(documentRepository.findById(9L)).thenReturn(Optional.of(doc));

        assertNull(adapter.openActive(9L));
        verifyNoInteractions(objectStoragePort);
    }
}
