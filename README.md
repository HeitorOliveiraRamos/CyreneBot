# 🤖 CyreneBot

**CyreneBot** é um bot de Discord desenvolvido em Java com a biblioteca [JDA (Java Discord API)](https://jda.wiki/introduction/jda/), projetado para interagir com modelos de inteligência artificial através da API do [Ollama](https://ollama.com/). Com fácil integração e alto grau de personalização, este bot oferece uma ponte entre usuários do Discord e capacidades modernas de IA.

---

## ✨ Funcionalidades

- Conecta canais do Discord a modelos de IA locais ou remotos usando Ollama.
- Mensagens contextuais com **personalidade customizável** via variável de ambiente.
- Arquitetura simples, extensível e escrita puramente em **Java**.

---

## 🚀 Requisitos

Antes de rodar o CyreneBot, verifique se os seguintes requisitos estão atendidos:

- Java 17 ou superior
- [Ollama](https://ollama.com/) instalado e em execução
- Token de bot do Discord (obtenha via [Discord Developer Portal](https://discord.com/developers/applications))
- Maven para gerenciamento de dependências

---

## ⚙️ Configuração

O bot utiliza variáveis de ambiente para configuração. Você deve definir:

| Variável             | Obrigatória | Descrição                                                   |
|----------------------|-------------|-------------------------------------------------------------|
| `BOT_TOKEN`      | ✅ Sim      | Token do bot do Discord                                     |
| `MODEL_NAME`       | ✅ Sim      | Nome do modelo a ser utilizado pelo Ollama (ex: `llama3`)   |
| `BOT_PERSONALITY`    | ❌ Opcional | Personalidade do bot, define o estilo de resposta da IA     |

### Exemplo (Unix/macOS)

```bash
export DISCORD_TOKEN="seu_token_aqui"
export OLLAMA_MODEL="llama3"
export BOT_PERSONALITY="Você é um bot sarcástico que responde com ironia."
