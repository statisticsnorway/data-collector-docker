module no.ssb.dc.server {

    requires no.ssb.service.provider.api;
    requires no.ssb.config;
    requires no.ssb.dc.api;
    requires no.ssb.dc.core;
    requires no.ssb.dc.application;
    requires no.ssb.dc.content.rawdata;
    requires no.ssb.rawdata.api;
    requires no.ssb.rawdata.postgres;
    requires no.ssb.rawdata.avro;
    requires no.ssb.rawdata.kafka;

    requires java.instrument;

    requires net.bytebuddy;
    requires net.bytebuddy.agent;
    requires org.slf4j;
    requires com.fasterxml.jackson.databind;
    requires jul_to_slf4j;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires logstash.logback.encoder;
    requires io.github.classgraph;

    requires undertow.core;

    opens no.ssb.dc.server;
    opens worker.config;

    exports no.ssb.dc.server;
    exports no.ssb.dc.server.controller;
    exports no.ssb.dc.server.service;
}
