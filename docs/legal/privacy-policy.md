# Política de Privacidade - Gyst

**Vigência:** 14 de julho de 2026

**Versão da política:** 1.5.0

O Gyst é um aplicativo de controle financeiro pessoal com funcionamento local por padrão (`offline-first`). Recursos de Google Drive e inteligência artificial são opcionais e iniciados/configurados pelo usuário.

## 1. Dados tratados no dispositivo

O aplicativo pode tratar os seguintes dados:

- orçamento mensal, categorias, despesas, assinaturas, parcelamentos e preferências;
- conversas com o consultor, incluindo títulos, mensagens, estados de envio/erro e provedor/modelo usados;
- perfis de provedor, endereço de API, modelo e capacidades declaradas, sem armazenar a chave no banco financeiro;
- imagens de comprovantes, faturas ou extratos selecionadas ou capturadas expressamente para uma importação;
- rascunhos extraídos, avisos de validação, hashes de origem e metadados de procedência da importação;
- no Android, quando a detecção automática está ativada e o aplicativo de origem foi permitido: pacote de origem, chave/identificador da notificação, horário, título, texto principal/expandido e categoria/canal necessários à filtragem e deduplicação;
- dados básicos da conta Google (nome, e-mail e foto) somente após login, além do horário e estado da sincronização.

A detecção Android descarta localmente, antes do armazenamento durável, notificações identificadas como mensagens, chamadas, mídia, conteúdo irrelevante ou códigos de autenticação/OTP. Sequências longas semelhantes a números de cartão/conta são ocultadas, mantendo no máximo os quatro últimos dígitos quando necessário para revisão.

## 2. Finalidades

Esses dados são usados para:

- calcular e exibir o planejamento financeiro;
- manter os registros e as conversas no dispositivo entre reinicializações e atualizações;
- extrair rascunhos de transações de imagens escolhidas pelo usuário;
- detectar localmente possíveis transações em notificações Android permitidas;
- apresentar rascunhos editáveis, alertas de duplicidade e uma notificação de revisão;
- inserir uma despesa somente após confirmação explícita;
- sincronizar ou restaurar um backup no Google Drive, quando solicitado.

## 3. Uso de provedores de inteligência artificial (BYOK)

O Gyst não escolhe nem chama um provedor ocultamente. O usuário informa sua própria chave, seleciona o provedor/modelo e inicia o uso.

Conforme o recurso usado, o provedor selecionado pode receber:

- **Consultor:** pergunta/mensagens da conversa selecionada, instruções de segurança e o contexto financeiro relevante exibido pelo produto;
- **Importação por imagem:** bytes completos das imagens que o usuário acabou de selecionar, tipo de arquivo, idioma/moeda esperados, instruções e esquema estruturado de extração;
- **Análise opcional de notificação:** identificador do pacote de origem, texto financeiro relevante já normalizado e limitado, idioma/contexto e esquema estruturado. Não são enviados chave/ID Android da notificação, lista de apps instalados, outras notificações, banco completo, OTP ou números completos de conta/cartão.

Antes de qualquer chamada de notificação, regras locais em português e inglês eliminam conteúdo irrelevante ou sensível. Desativar a análise por IA mantém a detecção local e cancela trabalhos de rede pendentes. O Gyst não troca silenciosamente para outro provedor.

A chave de API autentica diretamente no endpoint configurado. O tratamento e a eventual retenção pelo provedor seguem a política desse provedor; revise-a antes do uso.

## 4. Armazenamento, retenção e backup

- Os dados duráveis ficam no banco local do aplicativo.
- Imagens ficam em cache privado temporário, limitado por tamanho, e são removidas ao concluir/cancelar a importação ou após 24 horas. Rascunhos não confirmados expirados também são removidos do banco; importações concluídas mantêm apenas hash e procedência necessários, nunca a imagem.
- Conteúdo de notificação é limitado e mantido apenas durante processamento/revisão; texto bruto deixa de ser necessário e é apagado/redigido, enquanto impressão digital e campos do rascunho podem permanecer para evitar duplicidades.
- Chaves de provedor ficam fora do banco: Android usa armazenamento protegido pelo Android Keystore; os demais hosts usam armazenamento local protegido pela plataforma. Chaves não entram no backup do Google Drive.
- Ao habilitar o Google Drive, o banco durável — incluindo registros financeiros, conversas e rascunhos/metadados ainda existentes — pode ser copiado para o espaço privado `appDataFolder` da conta. Imagens temporárias e chaves de API não fazem parte desse banco.

## 5. Permissões Android

Acesso às notificações e permissão para o Gyst mostrar notificações são autorizações independentes:

- **Acesso às notificações:** permite que o serviço oficial `NotificationListenerService` receba notificações elegíveis dos apps permitidos. Sem ele, não há novas detecções.
- **Mostrar notificações (`POST_NOTIFICATIONS`, Android 13+):** permite exibir o aviso de que existe uma possível transação para revisar. Se negada, o rascunho ainda pode ficar disponível dentro do app.

O Gyst não usa serviço de acessibilidade, API privada ou varredura periódica para ler notificações. Consulte o [guia de detecção automática](../android-transaction-detection.md).

## 6. Compartilhamento

- O Gyst não vende dados pessoais e não compartilha dados para publicidade.
- Dados saem do dispositivo somente nos fluxos opcionais escolhidos pelo usuário: backup no Google Drive ou requisição ao provedor BYOK configurado.
- O Google Drive usa o espaço privado do aplicativo (`appDataFolder`).
- O backup automático do sistema Android é desativado; a transferência para o Google Drive acontece somente quando você usa o recurso de sincronização do Gyst.

## 7. Segurança e registros de diagnóstico

São aplicadas medidas razoáveis como chaves separadas do banco, integridade por chaves estrangeiras, idempotência, filtragem local e notificações com visibilidade privada na tela bloqueada.

Logs podem registrar identificadores locais, estado e tipo de erro necessários ao diagnóstico. Nunca devem registrar chave de API, payload completo do provedor, imagem financeira completa, corpo completo de notificação, OTP, número completo de conta/cartão ou stack trace exibido ao usuário. Nenhum sistema é totalmente isento de risco.

## 8. Controles e direitos do usuário

Você pode:

- editar/excluir registros e conversas no app;
- cancelar uma importação sem inserir transações;
- aprovar, rejeitar ou excluir rascunhos detectados;
- apagar dados derivados de notificações e sua proveniência; despesas já aprovadas permanecem como registros comuns até serem excluídas normalmente;
- desativar separadamente análise por IA, alertas de revisão ou toda a detecção;
- revogar o acesso às notificações e a permissão de alertas nas configurações Android;
- substituir/remover a chave BYOK e desconectar a conta Google;
- remover backups no Google Drive;
- apagar dados locais ou desinstalar o aplicativo.

## 9. Crianças

O app não é direcionado a menores de 13 anos.

## 10. Alterações nesta política

Esta política pode ser atualizada quando recursos ou práticas mudarem. A versão vigente ficará neste repositório e na página pública do projeto.

## 11. Contato

Para dúvidas sobre privacidade, use os canais do projeto (issues/discussões do repositório).
