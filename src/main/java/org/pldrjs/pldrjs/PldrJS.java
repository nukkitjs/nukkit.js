package org.pldrjs.pldrjs;

import cn.nukkit.Server;
import cn.nukkit.event.Event;
import cn.nukkit.plugin.PluginBase;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileFilter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleScriptContext;

public class PldrJS extends PluginBase{
	public File baseFolder = new File("scripts/");
	public File modulesFolder = new File(baseFolder, "node_modules");
	public CompiledScript commonjs;
	public String[] ignorantFiles = {"jvm-npm.js", "pldr.js"};
	public Map<String, Class<? extends Event>> knownEvents = new HashMap<>();
	public Map<String, String> scripts = new HashMap<>();
	private static PldrJS instance = null;
	private ScriptEngine engine = null;
	private ScriptContext ctx = null;
	public boolean isStopRequested = false;
	private boolean updateRequired = false;
	private Thread t = new Thread(() -> {
		while(!isStopRequested){
			try{
				engine.eval("$$.tickHook();", ctx);
				Thread.sleep(50);
			}catch(Exception e){
				e.printStackTrace();
			}
		}
	});

	public static PldrJS getInstance(){
		return instance;
	}

	public boolean exportResource(String resourceName, File target) throws Exception{
		if(!target.exists()){
			Files.copy(this.getClass().getClassLoader().getResourceAsStream(resourceName), target.toPath());
			return true;
		}
		return false;
	}

	@Override
	public void onDisable(){
		isStopRequested = true;
		if(updateRequired){
			new File(baseFolder, "jvm-npm.js").delete();
			new File(baseFolder, "pldr.js").delete();
			new File(baseFolder, "package.json").delete();
			deleteFolder(modulesFolder);
			return;
		}

		try{
			engine.eval("$$.disabledHook();", ctx);
		}catch(Exception e){
			e.printStackTrace();
		}
	}

	public void deleteFolder(File f){
		File[] innerFiles = f.listFiles();

		if(innerFiles.length <= 0) return;

		Arrays.asList(innerFiles).forEach((v) -> {
			if(v.isDirectory()) deleteFolder(v);
			else v.delete();
		});

		f.delete();
	}

	private List<String> findClass(String parentPackage) throws Exception{
		List<String> files = new LinkedList<>();
		String packageName = parentPackage.replace(".", "/");
		URL resources = Server.class.getClassLoader().getResources(packageName).nextElement();
		if(!resources.toString().startsWith("jar")){
			// not packaged with jar
			for(File dir : new File(resources.getFile()).listFiles()){
				if(!dir.isDirectory()) continue;

				for(File v : dir.listFiles(new FileFilter(){
					@Override
					public boolean accept(File f){
						return f.isFile();
					}
				})){
					files.add(packageName + "/" + dir.getName() + "/" + v.getName());
				}
			}
		}else{
			Enumeration<JarEntry> iter = ((JarURLConnection) resources.openConnection()).getJarFile().entries();
			while(iter.hasMoreElements()){
				files.add(iter.nextElement().getName());
			}
		}

		return files.stream().filter((file) -> {
			if(file.contains("$")) return false;
			return file.endsWith(".class") && file.startsWith(packageName);
		}).collect(Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onEnable(){
		instance = this;
		engine = new ScriptEngineManager().getEngineByName("nashorn");

		if(!baseFolder.exists()){
			baseFolder.mkdir();
		}
		boolean test = (System.getenv("PLDR_ENVIRONMENT") != null) && System.getenv("PLDR_ENVIRONMENT").toLowerCase().equals("development");

		try{
			exportResource("commonjs/src/main/javascript/jvm-npm.js", new File(baseFolder, "jvm-npm.js"));
			exportResource("pldr.js", new File(baseFolder, "pldr.js"));
			exportResource("package.json", new File(baseFolder, "package.json"));

			if(!modulesFolder.exists()) modulesFolder.mkdir();

			InputStream defaultModules = this.getClass().getClassLoader().getResourceAsStream("default_modules.zip");
			ZipInputStream zis = new ZipInputStream(defaultModules);
			ZipEntry entry;
			while((entry = zis.getNextEntry()) != null){
				File target = new File(modulesFolder, entry.getName());
				if(target.exists()) continue;
				if(entry.isDirectory()){
					target.mkdir();
				}else{
					Files.copy(zis, target.toPath());
				}
				zis.closeEntry();
			}
		}catch(Exception e){
			this.getLogger().error("리소스 추출 중에 오류가 발생했습니다.", e);
			return;
		}

		try{
			this.getLogger().info("\u00a7b" + "업데이트 체크중.... 시간이 오래 걸릴 수 있습니다.");
			URL versionCheck = new URL("https://raw.githubusercontent.com/pldrjs/pldr.js/master/package.json");
			String serverPackage = (new BufferedReader(new InputStreamReader(versionCheck.openStream()))).lines().collect(Collectors.joining("\n"));
			Gson gson = new Gson();
			JsonParser parser = new JsonParser();
			String serverVersion = gson.fromJson(parser.parse(serverPackage).getAsJsonObject().get("version"), String.class);

			String localPackage = Files.lines(new File(baseFolder, "package.json").toPath()).collect(Collectors.joining("\n"));
			String localVersion = gson.fromJson(parser.parse(localPackage).getAsJsonObject().get("version"), String.class);

			if(!localVersion.equals(serverVersion)){
				this.getLogger().warning("업데이트가 발견되었습니다!");
			}

			String packagedPackage = (new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("package.json")))).lines().collect(Collectors.joining("\n"));
			String packagedVersion = gson.fromJson(parser.parse(packagedPackage).getAsJsonObject().get("version"), String.class);

			if(!localVersion.equals(packagedVersion)){
				this.getLogger().info("\u00a7b" + "pldr.js는 업데이트 되었습니다.");
				updateRequired = true;
				this.getLogger().info("\u00a7b" + "pldr.js의 업데이트를 끝마치기 위해 서버를 종료합니다. 다시 시작하여주세요.");
				this.getServer().shutdown();
				return;
			}
		}catch(Exception e){
			this.getLogger().warning("업데이트 체크 중 오류가 발생했습니다.");
		}

		try{
			commonjs = ((Compilable) engine).compile(new FileReader(new File(baseFolder, "jvm-npm.js")));
		}catch(Exception e){
			this.getLogger().error("jvm-npm 스크립트를 컴파일 하던 도중 오류가 발생했습니다.", e);
		}

		try{
			findClass("cn.nukkit.event").forEach(file -> {
				String[] split = file.split("/");
				if(split.length != 5) return;
				if(!split[2].equals("event")) return;
				String category = split[3];
				String className = split[4].substring(0, split[4].length() - 6);

				if(file.equals("cn/nukkit/event/" + category + "/" + category.substring(0, 1).toUpperCase() + category.substring(1) + "Event.class")) return;

				try {
					String eventName = className.substring(0, className.length() - 5).toLowerCase();
					if(knownEvents.containsKey(eventName)) return;
					knownEvents.put(eventName, (Class<? extends Event>) Class.forName(file.substring(0, file.length() - 6).replace("/", ".")));
				} catch (Exception e) {
					this.getLogger().error("이벤트 목록을 순환 중에 오류가 발생했습니다.", e);
				}
			});
		}catch(Exception e){
			this.getLogger().error("이벤트를 찾는 도중 오류가 발생했습니다.", e);
		}

		Arrays.asList(baseFolder.listFiles()).forEach((f) -> {
			try{
				if(!(f.isFile() && f.getName().endsWith(".js"))
					|| (!test && Arrays.asList(ignorantFiles).contains(f.getName()))){
					return;
				}

				String[] split = f.getName().split("\\.");
				String name = IntStream.range(0, split.length).filter((v) -> {
					return v != split.length - 1;
				}).mapToObj((v) -> {
					return split[v];
				}).collect(Collectors.joining("."));

				scripts.put(name, Files.lines(f.toPath()).collect(Collectors.joining("\n")));
			}catch(Exception e){
				this.getLogger().error("스크립트를 읽는 도중 오류가 발생했습니다.", e);
			}
		});

		try{
			ctx = new SimpleScriptContext();
			ctx.getBindings(ScriptContext.ENGINE_SCOPE).put("PldrJSPlugin", PldrJS.getInstance());
			ctx.getBindings(ScriptContext.ENGINE_SCOPE).put("PldrJSEvents", knownEvents);
			ctx.getBindings(ScriptContext.ENGINE_SCOPE).put("PldrJSScripts", scripts);
			commonjs.eval(ctx);
			engine.eval(Files.lines((new File(baseFolder, "node_modules/babel-polyfill/dist/polyfill.min.js")).toPath()).collect(Collectors.joining("\n")), ctx);
			//engine.eval("Require.root = `" + baseFolder.getAbsolutePath() + "`;");
			engine.eval("require.root = \"" + baseFolder.getAbsolutePath().replace("\\", "\\\\") + "\";", ctx);
			//require('babel-polyfill') not working
			try{
				engine.eval("var PldrJS = require('./pldr');\nvar $$ = PldrJS;", ctx);
			}catch(Exception e){
				this.getLogger().error("pldr.js 스크립트 시작 도중 오류가 발생했습니다.", e);
			}

			scripts.forEach((k, v) -> {
				try{
					engine.eval("Function(PldrJSScripts.get('" + k.replace("'", "\\'") + "'))()", ctx);
					this.getLogger().info("\u00a7b" + k + "스크립트가 성공적으로 로딩됐습니다.");
				}catch(Exception e){
					this.getLogger().error(k + " 스크립트에 오류가 있습니다.", e);
				}
			});
		}catch(Exception e){
			this.getLogger().error("스크립트들을 실행시키는 도중에 오류가 발생했습니다.", e);
		}

		if(!test){
			t.setName("PldrJS modTick Thread");
			t.start();
		}
	}
}
