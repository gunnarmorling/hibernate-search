package org.hibernate.search.test.integration.jdk9_modules.client.service;

public class MyEntityServiceMain {

    public static void main(String[] args) {
        MyEntityService service = new MyEntityService();
        service.add( 1, "foo" );
        service.add( 2, "bar" );
        service.add( 3, "foo bar" );
        assert 2 == service.search( "foo" ).size();
        assert false;
    }
}
