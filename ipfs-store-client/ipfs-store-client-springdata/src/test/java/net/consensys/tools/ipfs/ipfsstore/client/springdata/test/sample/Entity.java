package net.consensys.tools.ipfs.ipfsstore.client.springdata.test.sample;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class Entity {

    private String id;

    private String hash;

    private String name;

    private int age;

}
