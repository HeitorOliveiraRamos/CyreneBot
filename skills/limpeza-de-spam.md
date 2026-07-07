# Limpeza de spam

Limpa uma onda de spam no canal: apaga as mensagens recentes e ativa modo lento temporário.

## Passos

1. Confira no bloco "Permissões de moderação" do solicitante se ele pode gerenciar
   mensagens. Se não puder, pare aqui e reporte a recusa em uma frase.
2. Chame `purgeMessages` com a quantidade que o usuário pediu (se não disse, use 30).
3. Chame `setSlowmode` com 30 segundos para conter o restante da onda.
4. Descreva factualmente o que foi feito em uma frase: quantas mensagens foram
   apagadas e o slowmode aplicado. Se alguma ferramenta retornou ok=false, reporte
   a falha em vez de tentar de novo.
