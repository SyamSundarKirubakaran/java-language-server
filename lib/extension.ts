'use strict';
// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as Path from "path";
import * as FS from "fs";
import { window, workspace, ExtensionContext, commands, tasks, Task, TaskExecution, ShellExecution, Uri, TaskDefinition, languages, IndentAction, Progress, ProgressLocation } from 'vscode';
import {LanguageClient, LanguageClientOptions, ServerOptions, NotificationType} from "vscode-languageclient";

// If we want to profile using VisualVM, we have to run the language server using regular java, not jlink
// This is intended to be used in the 'F5' debug-extension mode, where the extension is running against the actual source, not build.vsix
const visualVm = false;

/** Called when extension is activated */
export function activate(context: ExtensionContext) {
    console.log('Activating Java');
    
    // Options to control the language client
    let clientOptions: LanguageClientOptions = {
        // Register the server for java documents
        documentSelector: ['java'],
        synchronize: {
            // Synchronize the setting section 'java' to the server
            // NOTE: this currently doesn't do anything
            configurationSection: 'java',
            // Notify the server about file changes to 'javaconfig.json' files contain in the workspace
            fileEvents: [
                workspace.createFileSystemWatcher('**/javaconfig.json'),
                workspace.createFileSystemWatcher('**/pom.xml'),
                workspace.createFileSystemWatcher('**/WORKSPACE'),
                workspace.createFileSystemWatcher('**/BUILD'),
                workspace.createFileSystemWatcher('**/*.java')
            ]
        },
        outputChannelName: 'Java',
        revealOutputChannelOn: 4 // never
    }

    let launcherRelativePath = platformSpecificLauncher();
    let launcherPath = [context.extensionPath].concat(launcherRelativePath);
    let launcher = Path.resolve(...launcherPath);
    
    console.log(launcher);
    
    // Start the child java process
    let serverOptions: ServerOptions = {
        command: launcher,
        args: [],
        options: { cwd: context.extensionPath }
    }

    if (visualVm) {
        serverOptions = visualVmConfig(context);
    }

    // Copied from typescript
    languages.setLanguageConfiguration('java', {
        indentationRules: {
            // ^(.*\*/)?\s*\}.*$
            decreaseIndentPattern: /^((?!.*?\/\*).*\*\/)?\s*[\}\]\)].*$/,
            // ^.*\{[^}"']*$
            increaseIndentPattern: /^((?!\/\/).)*(\{[^}"'`]*|\([^)"'`]*|\[[^\]"'`]*)$/,
            indentNextLinePattern: /^\s*(for|while|if|else)\b(?!.*[;{}]\s*(\/\/.*|\/[*].*[*]\/\s*)?$)/
        },
        onEnterRules: [
            {
                // e.g. /** | */
                beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
                afterText: /^\s*\*\/$/,
                action: { indentAction: IndentAction.IndentOutdent, appendText: ' * ' }
            }, {
                // e.g. /** ...|
                beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
                action: { indentAction: IndentAction.None, appendText: ' * ' }
            }, {
                // e.g.  * ...|
                beforeText: /^(\t|(\ \ ))*\ \*(\ ([^\*]|\*(?!\/))*)?$/,
                action: { indentAction: IndentAction.None, appendText: '* ' }
            }, {
                // e.g.  */|
                beforeText: /^(\t|(\ \ ))*\ \*\/\s*$/,
                action: { indentAction: IndentAction.None, removeText: 1 }
            },
            {
                // e.g.  *-----*/|
                beforeText: /^(\t|(\ \ ))*\ \*[^/]*\*\/\s*$/,
                action: { indentAction: IndentAction.None, removeText: 1 }
            }
        ]
    })

    // Create the language client and start the client.
    let client = new LanguageClient('java', 'Java Language Server', serverOptions, clientOptions);
    let disposable = client.start();

    // Push the disposable to the context's subscriptions so that the 
    // client can be deactivated on extension deactivation
    context.subscriptions.push(disposable);

    // Register test commands
    commands.registerCommand('java.command.test.run', runTest);
    commands.registerCommand('java.command.findReferences', runFindReferences);

	// When the language client activates, register a progress-listener
	client.onReady().then(() => createProgressListeners(client));
}

// this method is called when your extension is deactivated
export function deactivate() {
}

function runFindReferences(uri: string, lineNumber: number, column: number) {
    // LSP is 0-based but VSCode is 1-based
    return commands.executeCommand('editor.action.findReferences', Uri.parse(uri), {lineNumber: lineNumber+1, column: column+1});
}

interface JavaTestTask extends TaskDefinition {
    className: string
    methodName: string
}

function runTest(sourceUri: string, className: string, methodName: string): Thenable<TaskExecution> {
    let file = Uri.parse(sourceUri).fsPath;
    file = Path.relative(workspace.rootPath, file);
	let kind: JavaTestTask = {
		type: 'java.task.test',
        className: className,
        methodName: methodName,
    }
    var shell;
    let config = workspace.getConfiguration('java')
    // Run method or class
    if (methodName != null) {
        let command = config.get('testMethod') as string[]
        if (command.length == 0) {
            window.showErrorMessage('Set "java.testMethod" in .vscode/settings.json')
            shell = new ShellExecution('echo', ['Set "java.testMethod" in .vscode/settings.json, for example ["mvn", "test", "-Dtest=${class}#${method}"]'])
        } else {
            shell = templateCommand(command, file, className, methodName)
        }
    } else {
        let command = config.get('testClass') as string[]
        if (command.length == 0) {
            window.showErrorMessage('Set "java.testClass" in .vscode/settings.json')
            shell = new ShellExecution('echo', ['Set "java.testClass" in .vscode/settings.json, for example ["mvn", "test", "-Dtest=${class}"]'])
        } else {
            shell = templateCommand(command, file, className, methodName)
        }
    }
	let workspaceFolder = workspace.getWorkspaceFolder(Uri.parse(sourceUri))
	let task = new Task(kind, workspaceFolder, 'Java Test', 'Java Language Server', shell)
	return tasks.executeTask(task)
}

function templateCommand(command: string[], file: string, className: string, methodName: string) {
    // Replace template parameters
    var replaced = []
    for (var i = 0; i < command.length; i++) {
        let c = command[i]
        c = c.replace('${file}', file)
        c = c.replace('${class}', className)
        c = c.replace('${method}', methodName)
        replaced[i] = c
    }
    // Populate env
    let env = {...process.env} as {[key: string]: string};
    return new ShellExecution(replaced[0], replaced.slice(1), {env})
}

interface ProgressMessage {
    message: string 
    increment: number
}

function createProgressListeners(client: LanguageClient) {
	// Create a "checking files" progress indicator
	let progressListener = new class {
		progress: Progress<{message: string, increment?: number}>
		resolve: (nothing: {}) => void
		
		startProgress(message: string) {
            if (this.progress != null)
                this.endProgress();

            window.withProgress({title: message, location: ProgressLocation.Notification}, progress => new Promise((resolve, _reject) => {
                this.progress = progress;
                this.resolve = resolve;
            }));
		}
		
		reportProgress(message: string, increment: number) {
            if (increment == -1)
                this.progress.report({message});
            else 
                this.progress.report({message, increment})
		}

		endProgress() {
            if (this.progress != null) {
                this.resolve({});
                this.progress = null;
                this.resolve = null;
            }
		}
	}
	// Use custom notifications to drive progressListener
	client.onNotification(new NotificationType('java/startProgress'), (event: ProgressMessage) => {
		progressListener.startProgress(event.message);
	});
	client.onNotification(new NotificationType('java/reportProgress'), (event: ProgressMessage) => {
		progressListener.reportProgress(event.message, event.increment);
	});
	client.onNotification(new NotificationType('java/endProgress'), () => {
		progressListener.endProgress();
	});
}

function platformSpecificLauncher(): string[] {
	switch (process.platform) {
		case 'win32':
            return ['dist', 'windows', 'bin', 'launcher'];

        case 'darwin':
            return ['dist', 'mac', 'bin', 'launcher'];
	}

	throw `unsupported platform: ${process.platform}`;
}

// Alternative server options if you want to use visualvm
function visualVmConfig(context: ExtensionContext): ServerOptions {
    let javaExecutablePath = findJavaExecutable('java');
    
    if (javaExecutablePath == null) {
        window.showErrorMessage("Couldn't locate java in $JAVA_HOME or $PATH");
        
        throw "Gave up";
    }

    let classes = Path.resolve(context.extensionPath, "target", "classes");
    let cpTxt = Path.resolve(context.extensionPath, "target", "cp.txt");
    let cpContents = FS.readFileSync(cpTxt, "utf-8");
    
    let args = [
        '-cp', classes + ":" + cpContents, 
        '-Xverify:none', // helps VisualVM avoid 'error 62'
        '-Xdebug',
        // '-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005',
        'org.javacs.Main',
        "--add-exports", "jdk.compiler/com.sun.tools.javac.api=javacs",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=javacs",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.comp=javacs",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.main=javacs",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=javacs",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.model=javacs",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=javacs",
    ];
    
    console.log(javaExecutablePath + ' ' + args.join(' '));
    
    // Start the child java process
    return {
        command: javaExecutablePath,
        args: args,
        options: { cwd: context.extensionPath }
    }
}

function findJavaExecutable(binname: string) {
	binname = correctBinname(binname);

	// First search java.home setting
    let userJavaHome = workspace.getConfiguration('java').get('home') as string;

	if (userJavaHome != null) {
        console.log('Looking for java in settings java.home ' + userJavaHome + '...');

        let candidate = findJavaExecutableInJavaHome(userJavaHome, binname);

        if (candidate != null)
            return candidate;
	}

	// Then search each JAVA_HOME
    let envJavaHome = process.env['JAVA_HOME'];

	if (envJavaHome) {
        console.log('Looking for java in environment variable JAVA_HOME ' + envJavaHome + '...');

        let candidate = findJavaExecutableInJavaHome(envJavaHome, binname);

        if (candidate != null)
            return candidate;
	}

	// Then search PATH parts
	if (process.env['PATH']) {
        console.log('Looking for java in PATH');
        
		let pathparts = process.env['PATH'].split(Path.delimiter);
		for (let i = 0; i < pathparts.length; i++) {
			let binpath = Path.join(pathparts[i], binname);
			if (FS.existsSync(binpath)) {
				return binpath;
			}
		}
	}
    
	// Else return the binary name directly (this will likely always fail downstream) 
	return null;
}

function correctBinname(binname: string) {
	if (process.platform === 'win32')
		return binname + '.exe';
	else
		return binname;
}

function findJavaExecutableInJavaHome(javaHome: string, binname: string) {
    let workspaces = javaHome.split(Path.delimiter);

    for (let i = 0; i < workspaces.length; i++) {
        let binpath = Path.join(workspaces[i], 'bin', binname);

        if (FS.existsSync(binpath)) 
            return binpath;
    }

    return null;
}