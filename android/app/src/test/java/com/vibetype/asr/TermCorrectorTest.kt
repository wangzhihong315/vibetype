package com.vibetype.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TermCorrectorTest {

    private val corrector = TermCorrector(
        File("src/main/assets/terms.json").readText()
    )

    private fun fix(input: String): String = corrector.correct(input).text

    @Test
    fun jsonSpokenForms() {
        assertEquals("我们用 JSON 传数据", fix("我们用 J song 传数据"))
        assertEquals("解析 JSON 文件", fix("解析 杰森 文件"))
        assertEquals("这是 JSON 格式", fix("这是 jason 格式"))
    }

    @Test
    fun dottedAndMultiWordTerms() {
        assertEquals("把 Node.js 升级一下", fix("把 node js 升级一下"))
        assertEquals("跑 Next.js 的构建", fix("跑 next.js 的构建"))
        assertEquals("用 GitHub Actions 部署", fix("用 github actions 部署"))
        assertEquals("用 Hugging Face 上的模型", fix("用 hugging face 上的模型"))
        assertEquals("C++ 和 C#", fix("c plus plus 和 c sharp"))
        assertEquals(".NET 和 ASP.NET", fix("dot net 和 asp dot net"))
        assertEquals("PostgreSQL 和 MySQL", fix("postgres 和 my sequel"))
    }

    @Test
    fun aiTerms() {
        assertEquals("微调一个 LLM", fix("微调一个 llm"))
        assertEquals("用 ChatGPT 和 Claude Code", fix("用 chat gpt 和 cloud code"))
        assertEquals("RAG 和 MCP", fix("rag 和 mcp"))
        assertEquals("用 Hugging Face 的 Transformer", fix("用 hugging face 的 transformer"))
        assertEquals("MoE 和 KV cache", fix("moe 和 kv cache"))
        assertEquals("MCP", fix("model context protocol"))
        assertEquals("MCP", fix("模型上下文协议"))
    }

    @Test
    fun letterNamesAndCamelCase() {
        assertEquals("调用 API 和 SDK", fix("调用 a p i 和 s d k"))
        assertEquals("useEffect 和 useState", fix("use effect 和 use state"))
        assertEquals("用 GraphQL 和 gRPC", fix("用 graph ql 和 g rpc"))
        assertEquals("JWT 和 OAuth", fix("j w t 和 o auth"))
    }

    @Test
    fun noSubstringDamage() {
        assertEquals("said hello", fix("said hello"))
        assertEquals("Node.js 和 Next.js", fix("Node.js 和 Next.js"))
        assertEquals("跑一遍 CI", fix("跑一遍词"))
    }
}
