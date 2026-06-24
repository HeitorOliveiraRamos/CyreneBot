# Arquitetura de Modelos - CyreneBot

## Visão Geral

O CyreneBot utiliza uma **arquitetura de 2 passes + portão de intenção** para separar a lógica de raciocínio (moderation/tools) da persona do bot. Dois dos três modelos podem ser diferentes, permitindo otimizações independentes.

```
Entrada do usuário
        ↓
   [ INTENT GATE ]  ← brain_model_name (determinístico, 1 palavra)
        ↓
    ┌───┴───┐
    │       │
   CHAT   MOD
    │       │
    └───┬───┘
        ↓
  [ BRAIN PASS ]  ← brain_model_name (reasoning, tools ligadas)
        ↓
  [ VOICE PASS ]  ← voice_model_name (persona, sem tools)
        ↓
   Resposta final
```

---

## Os 3 Modelos

### 1. **MODEL_NAME** (Modelo Padrão/Legacy)
- **Usado por:** `/contexto-do-canal` e outros caminhos de passa única
- **Características:**
  - Caminho único (sem passa de brain + voice)
  - Persona ativa
  - **SEM ferramentas** (tools desativadas para evitar que o sumarizador acione ações de moderação)
  - Resumir histórico recente do canal
  
- **Configuração:**
  ```yaml
  MODEL_NAME=llama3.1  # ou outro modelo tool-capable
  ```
  
- **Requisitos:** Deve ser tool-capable (mesmo que tools não sejam usadas)
  
- **Exemplo de uso:**
  ```
  Usuário: /contexto-do-canal
  Behavior: Sumariza últimas mensagens do canal COM persona
  ```

---

### 2. **BRAIN_MODEL_NAME** (Modelo de Raciocínio)
- **Usado por:**
  1. **Intent Gate** - Classificador binário ultrarrápido
  2. **Brain Pass** - Raciocínio com acesso a tools
  
- **Intent Gate:**
  - Classifica a última mensagem como "mod" ou "chat" em **uma palavra**
  - Temperatura = 0.0 (determinístico)
  - Token budget = 8 palavras máximo
  - Propósito: Evitar que o modelo hallucine ações de moderação em mensagens conversacionais
  
  ```
  Exemplos:
  "oi tudo bem?" → chat   (vai para voice-only)
  "muta o <@123> por 1h" → mod  (vai para brain+voice)
  ```

- **Brain Pass:**
  - Sem persona (persona é adicionada apenas na voice pass)
  - **Com ferramentas Discord** (timeout, kick, ban, consultas de permissão)
  - Sistema apenas: `BRAIN_INSTRUCTIONS` + `TOOL_USAGE_RULES`
  - Saída: descrição factual e neutra do que foi feito (ou o sentinel "Sem ação necessária")
  - Sampling: Temperatura baixa (0.1), TopP baixo (0.5) para evitar hallucinations
  
  ```
  Exemplo:
  Entrada: "muta o <@123456> por 10 minutos por xingar o bot"
  Brain output: "Timeout de 10 minutos aplicado em <@123456> por 'xingar o bot'."
  ```

- **Requisitos:**
  - **DEVE ser tool-capable** (llama3.1, llama3.2, qwen2.5, mistral-nemo, granite3-dense)
  - llama3 (sem .1) NÃO funciona - não suporta tools
  
- **Por que usar modelo diferente?**
  - No futuro, um modelo menor/mais rápido pode ser usado apenas para raciocínio
  - Sampling tight (temp=0.1) mantém o modelo focado
  - Sem distrações de persona permite que foque em ferramentas

---

### 3. **VOICE_MODEL_NAME** (Modelo de Persona)
- **Usado por:** Voice Pass (reescrita em personagem)
  
- **Características:**
  - **Com persona completa** (arquivo `cyrene-personality.md`)
  - **SEM ferramentas** - nunca pode chamar tools
  - Contexto: resultado factual do brain + histórico de conversa (em alguns casos)
  - Propósito: Reescrever saída do brain em personagem (PT-BR, Cyrene)
  - Sampling: Temperatura mais alta (0.8) para respostas mais naturais
  
  ```
  Entrada: "Timeout de 10 minutos aplicado em <@123456> por 'xingar o bot'."
  Voice output: 
    "Madura, <@123456>. Você vai pensar no que fez enquanto espera 10 minutos. 
     Próxima vez sem brincadeiras."
  ```

- **Dois modos:**
  
  **a) Focused (quando brain executou ação):**
  - Historia de conversa: EXCLUÍDA
  - Input: apenas o resultado do brain + diretiva "narrate this in character"
  - Razão: Model anchors na última mensagem do usuário ("muta") e recusa se tiver histórico completo
  
  **b) Conversational (quando brain retornou "Sem ação necessária"):**
  - Histórico de conversa: **INCLUÍDO**
  - Input: histórico completo + última turn do usuário
  - Razão: Para conversas normais ("oi", "qual seu nome?"), continua naturalmente
  
- **Requisitos:**
  - Pode ser **QUALQUER modelo** - não precisa ser tool-capable
  - Pode ser otimizado para qualidade de prosa/persona
  - Pode ser modelo menor/mais rápido que o brain (já que não faz raciocínio pesado)

- **Por que usar modelo diferente?**
  - No futuro, pode ser um modelo especializad em escrita criativa/persona
  - Pode ser menor e mais rápido (já que é só reescrita)
  - Pode ter temperatura/sampling diferentes (natural prose vs tight reasoning)

---

## Sentinelas Especiais do Brain

O brain pass usa dois sentinelas para comunicar com a voice pass:

| Sentinela | Significado | Voice Pass faz |
|-----------|------------|-----------------|
| `Sem ação necessária.` | Mensagem é puramente conversacional, sem ação a executar | Retorna histórico completo + persona para responder naturalmente |
| `Pronto.` | Brain tentou agir mas resultou em vazio | usa como fallback se voice também ficar vazio |

---

## Otimizações Possíveis

### Cenário 1: Máxima Performance
```dotenv
MODEL_NAME=llama3.1           # Legacy paths
BRAIN_MODEL_NAME=mistral-small  # Rápido, tool-capable
VOICE_MODEL_NAME=gemma4         # Otimizado para prosa, não precisa tools
```

**Vantagem:** Brain (classify + raciocínio) roda em modelo rápido, voice (persona) otimizado

### Cenário 2: Single Model (Padrão)
```dotenv
MODEL_NAME=llama3.1
BRAIN_MODEL_NAME=llama3.1
VOICE_MODEL_NAME=llama3.1
```

**Vantagem:** Simples, sem necessidade de download de múltiplos modelos

### Cenário 3: Alta Qualidade de Persona
```dotenv
MODEL_NAME=llama3.1
BRAIN_MODEL_NAME=llama3.1
VOICE_MODEL_NAME=qwen2.5  # Melhor em prosa criativa
```

**Vantagem:** Voice redige com mais qualidade, brain mantém confiabilidade

---

## Fluxo Completo: Exemplo Prático

### Exemplo 1: Comando de Moderação

```
USER: "muta o <@123456> por 10 minutos por spam"

1. INTENT GATE (brain_model_name)
   Input: "Mensagem: muta o <@123456> por 10 minutos por spam\nResposta:"
   Output: "mod"  ← classifica como moderação
   (Temp=0.0, max 8 tokens)

2. BRAIN PASS (brain_model_name + tools)
   System: BRAIN_INSTRUCTIONS + TOOL_USAGE_RULES (SEM persona)
   Tools: timeoutMember, kickMember, getGuildInfo, etc.
   Calls: timeoutMember(userId="123456", minutes=10, reason="spam")
   Output: "Timeout de 10 minutos aplicado em <@123456> por 'spam'."
   (Temp=0.1, tight sampling)

3. VOICE PASS (voice_model_name, NO tools)
   Historical: EXCLUDED (focused mode - brain executed something)
   System: persona + "Narrate this result in character"
   Input: "Resultado a comunicar em personagem: Timeout de 10 minutos aplicado..."
   Output: "Ó <@123456>, sua língua rápida vai ficar quieta por 10 minutos. 
            Volte quando aprender boas maneiras. 💜"
   (Temp=0.8, warm sampling)

FINAL RESPONSE TO USER: 
  "Ó <@123456>, sua língua rápida vai ficar quieta por 10 minutos. 
   Volte quando aprender boas maneiras. 💜"
```

### Exemplo 2: Pergunta Conversacional

```
USER: "qual é seu nome?"

1. INTENT GATE (brain_model_name)
   Output: "chat"  ← conversational, não é moderação
   → CORTA o brain pass inteiro!

2. VOICE PASS ONLY (voice_model_name, NO tools)
   System: persona completa
   History: **INCLUDED** (conversational mode - sem ação prévia)
   Output: "Sou a Cyrene. Prazer em conhecê-lo! 💜"

FINAL RESPONSE: "Sou a Cyrene. Prazer em conhecê-lo! 💜"
━━━━━━━━━━━━━━━ (sem passar pelo brain - mais rápido!)
```

---

## Configuração Recomendada Inicial

Para começar:

```dotenv
# Se tiver recursos limitados (um modelo):
MODEL_NAME=llama3.1
BRAIN_MODEL_NAME=llama3.1
VOICE_MODEL_NAME=llama3.1

# Se quiser otimizar:
MODEL_NAME=llama3.1
BRAIN_MODEL_NAME=llama3.1
VOICE_MODEL_NAME=gemma4  # ou outro modelo bom em prosa
```

**⚠️ Importante:**
- `BRAIN_MODEL_NAME` DEVE ser tool-capable
- `VOICE_MODEL_NAME` pode ser qualquer modelo
- `MODEL_NAME` deve ser tool-capable (mesmo que tools não sejam usadas)

---

## Resumo Visual

| Aspecto | MODEL_NAME | BRAIN_MODEL_NAME | VOICE_MODEL_NAME |
|---------|-----------|-----------------|-----------------|
| **Usado para** | /contexto-do-canal | Intent gate + raciocínio | Reescrita em persona |
| **Persona** | ✅ Sim | ❌ Não | ✅ Sim |
| **Tools** | ❌ Não | ✅ Sim | ❌ Não |
| **Tool-capable** | ✅ Obrigatório | ✅ Obrigatório | ❌ Opcional |
| **Temperatura** | padrão | Tight (0.1) | Warm (0.8) |
| **Token budget** | padrão | Alto (512) | padrão (512) |
| **Intent gate** | ❌ Não | ✅ Sim (det. 0.0) | ❌ Não |
| **Pode ser diferente** | Sim | Sim | Sim |


