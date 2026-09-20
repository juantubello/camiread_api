package net.casapipis.camireads.dto;

import lombok.Data;

/** Body de POST /tags. El slug NO se acepta del cliente: se deriva del name. */
@Data
public class NewTagRequest {
    private String name;
    private String color;
}
