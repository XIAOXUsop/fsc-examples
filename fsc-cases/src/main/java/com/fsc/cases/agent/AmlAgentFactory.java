package com.fsc.cases.agent;

import com.fsc.cases.rag.LegalRag;
import com.fsc.cases.tool.AmlTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

/**
 * 把 ChatModel、工具集与法规 RAG 装配成 {@link AmlAgent}。
 */
public final class AmlAgentFactory {

    private AmlAgentFactory() {
    }

    public static AmlAgent create(ChatModel chatModel, LegalRag rag) {
        return AiServices.builder(AmlAgent.class)
                .chatModel(chatModel)
                .tools(new AmlTools(rag))
                .contentRetriever(rag.contentRetriever())
                .build();
    }
}
