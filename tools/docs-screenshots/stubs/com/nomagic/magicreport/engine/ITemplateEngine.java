package com.nomagic.magicreport.engine;
public interface ITemplateEngine { void addContext(String k, Object v); com.nomagic.magicreport.Template createTemplate(java.io.File a, java.io.File b) throws Exception; void setProperty(String k, String v); void evaluate(com.nomagic.magicreport.Template t) throws Exception; void destroy(); }
