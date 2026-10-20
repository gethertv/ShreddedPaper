package io.papermc.paper.network;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import io.multipaper.shreddedpaper.ShreddedPaper;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.*;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.util.*;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.extension.NormalExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Normal")
@ExtendWith(ChatPacketOrderingTestSuite.EngineEnvironment.class)
class ChatPacketOrderingTestSuite {
    public static class EngineEnvironment extends NormalExtension {
        @Override public void init(ExtensionContext context) {
            var config=new ShreddedPaperConfiguration();
            config.optimizations=config.new Optimizations();
            config.multithreading=config.new Multithreading();
            config.optimizations.chunkPacketCaching=config.optimizations.new ChunkPacketCaching();
            try { field(null,ShreddedPaperConfiguration.class,"instance",config); }
            catch(Exception error) { throw new RuntimeException(error); }
            super.init(context);
        }
    }
    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field=owner.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }

    @Test void overtakingARealSignedBodyPermanentlyBreaksItsDecoder() throws Exception {
        var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        var pair=generator.generateKeyPair();var sender=UUID.randomUUID();var session=UUID.randomUUID();
        var encoder=new SignedMessageChain(sender,session).encoder(Signer.from(pair.getPrivate(),"SHA256withRSA"));
        var key=new net.minecraft.world.entity.player.ProfilePublicKey(new net.minecraft.world.entity.player.ProfilePublicKey.Data(
                Instant.now().plusSeconds(3600),pair.getPublic(),new byte[0]));
        var command=new SignedMessageBody("command",Instant.now(),1,LastSeenMessages.EMPTY);
        var chat=new SignedMessageBody("chat",Instant.now(),2,LastSeenMessages.EMPTY);
        var first=encoder.pack(command);var second=encoder.pack(chat);
        var ordered=new SignedMessageChain(sender,session).decoder(key);
        assertEquals("command",ordered.unpack(first,command).signedContent());
        assertEquals("chat",ordered.unpack(second,chat).signedContent());
        var overtaken=new SignedMessageChain(sender,session).decoder(key);
        var invalid=assertThrows(SignedMessageChain.DecodeException.class,()->overtaken.unpack(second,chat));
        assertEquals(Component.translatable("chat.disabled.invalid_signature"),invalid.getComponent());
        var broken=assertThrows(SignedMessageChain.DecodeException.class,()->overtaken.unpack(first,command));
        assertEquals(Component.translatable("chat.disabled.chain_broken"),broken.getComponent());
    }

    @Test void commandChatAndAckKeepWireOrderWhenOwnerTickHasNotRunYet() throws Exception {
        var handler=mock(ServerGamePacketListenerImpl.class,CALLS_REAL_METHODS);
        var server=mock(MinecraftServer.class);var player=mock(ServerPlayer.class);
        var level=mock(ServerLevel.class);var craft=mock(CraftServer.class);
        handler.player=player;
        field(handler,ServerCommonPacketListenerImpl.class,"server",server);
        field(handler,ServerCommonPacketListenerImpl.class,"cserver",craft);
        field(handler,ServerGamePacketListenerImpl.class,"chatMessageChain",mock(FutureChain.class));
        field(handler,ServerGamePacketListenerImpl.class,"chatSpamThrottler",new TickThrottler(20,200));
        field(server,MinecraftServer.class,"chatExecutor",mock(ExecutorService.class));
        when(player.level()).thenReturn(level);
        when(player.getChatVisibility()).thenReturn(ChatVisiblity.FULL);
        when(player.getGameProfile()).thenReturn(new GameProfile(UUID.randomUUID(),"Qa_Chat"));
        doReturn(mock(CraftPlayer.class)).when(handler).getCraftPlayer();
        doReturn(true).when(handler).isAcceptingMessages();
        when(craft.getPluginManager()).thenReturn(mock(PluginManager.class));
        var commands=mock(Commands.class);when(server.getCommands()).thenReturn(commands);
        var dispatcher=new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(Commands.literal("say").then(Commands.argument("message",MessageArgument.message())));
        when(commands.getDispatcher()).thenReturn(dispatcher);
        var source=mock(CommandSourceStack.class,RETURNS_SELF);when(player.createCommandSourceStack()).thenReturn(source);
        var filter=mock(TextFilter.class);when(player.getTextFilter()).thenReturn(filter);
        when(filter.processStreamMessage(anyString())).thenReturn(new CompletableFuture<>());
        var decorator=mock(ChatDecorator.class);when(server.getChatDecorator()).thenReturn(decorator);
        when(decorator.decorate(any(),any(),any())).thenReturn(new CompletableFuture<>());
        List<String> order=new ArrayList<>();
        var decoder=mock(SignedMessageChain.Decoder.class);
        when(decoder.unpack(any(),any())).thenAnswer(call->{
            SignedMessageBody body=call.getArgument(1);order.add(body.content());
            return PlayerChatMessage.unsigned(UUID.randomUUID(),body.content());
        });
        field(handler,ServerGamePacketListenerImpl.class,"signedMessageDecoder",decoder);
        var seen=spy(new LastSeenMessagesValidator(20));
        doAnswer(call->{order.add("ack");return call.callRealMethod();}).when(seen).applyOffset(1);
        field(handler,ServerGamePacketListenerImpl.class,"lastSeenMessages",seen);
        // A pending outgoing message makes a later ACK valid, but it must not overtake the two bodies.
        var signature=new MessageSignature(new byte[256]);seen.addPending(signature);
        var update=new LastSeenMessages.Update(0,new BitSet(),(byte)0);
        var command=new ServerboundChatCommandSignedPacket("say command",Instant.now(),1,
                new ArgumentSignatures(List.of(new ArgumentSignatures.Entry("message",signature))),update);
        var chat=new ServerboundChatPacket("chat",Instant.now(),2,signature,update);
        var ack=new ServerboundChatAckPacket(1);
        ArrayDeque<Runnable> ownerQueue=new ArrayDeque<>();boolean[] owner={false};
        try(var packets=mockStatic(PacketUtils.class);var scheduler=mockStatic(ShreddedPaper.class)) {
            packets.when(()->PacketUtils.ensureRunningOnSameThread(any(Packet.class),eq(handler),eq(level))).thenAnswer(call->{
                if(!owner[0]) {
                    Packet<ServerGamePacketListener> packet=call.getArgument(0);
                    ownerQueue.add(()->packet.handle(handler));
                    throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
                }
                return null;
            });
            scheduler.when(()->ShreddedPaper.runSync(eq(player),any(Runnable.class))).thenAnswer(call->{ownerQueue.add(call.getArgument(1));return null;});
            scheduler.when(()->ShreddedPaper.ensureSync(eq(player),any(Runnable.class))).thenAnswer(call->{
                Runnable task=call.getArgument(1);if(owner[0])task.run();else ownerQueue.add(task);return null;
            });
            for(Packet<ServerGamePacketListener> packet:List.of(command,chat,ack)) {
                try { packet.handle(handler); } catch(RunningOnDifferentThreadException expected) { }
            }
            assertTrue(order.isEmpty(),"Nothing may mutate the signed chain or ACK window ahead of the owner tick");
            owner[0]=true;while(!ownerQueue.isEmpty())ownerQueue.remove().run();
            assertEquals(List.of("command","chat","ack"),order);
        }
    }
}
