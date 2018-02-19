# TODO Titel
# TODO Abstract (1-2 Sätze)

---


# Einleitung

Wer Jenkins als Continuous Integration Server verwendet, ist vermutlich längst auf Jenkins Pipelines umgestiegen oder plant eine Migration zu Pipeline as Code in naher Zukunft. Gerade im Zeitalter von Microservices aber auch bei 'klassischen' Architekturen besteht das zu entwickelnde System häufig aus mehreren Projekten, die alle eine eigene Delivery Pipeline in Jenkins benötigen. Man wird schnell feststellen, das in den verschiedenen Pipelines sehr ähnliche Aufgaben anfallen. Daher ist es sinnvoll die Funktionalität zentral für alle Projekte bereitzustellen. Jenkins bietet dafür eine einfache und saubere Lösung: [Shared Libaries](TODO). 

In diesem Blogpost stellen wir anhand eines Beispiels vor, wie Pipeline Code extrahiert und wiederverwendet werden kann. Danach gehen wir noch einen Schritt weiter und zeigen wie auf Basis der Shared Libaries, durch Erweiterung der Jenkinsfile DSL, eine kurze und prägnate Deklaration von Pipelines entstehen kann.

Wer sich zuerst in die Grundlagen von Jenkinsfiles einlesen möchte, dem empfehlen wir den Blogpost
[Saubere CI-Builds mit Jenkins Pipeline Jobs ](https://comsysto.com/blog-post/saubere-ci-builds-mit-jenkins-pipeline-jobs-docker-und-blue-ocean) auf unsere Webseite sowie die offizielle Jenkins [Pipeline Dokumention](https://jenkins.io/doc/book/pipeline/).


# Ausgangsituation

Als Ausgangsituation haben wir zwei einfache Spring Boot Applikationen (Service 1 & Service 2) die mit Hilfe von Jenkins gebaut, und deployt werden sollen. Die beiden Pipelines haben nahezu die gleiche Funktionalität, die Implementierungen im jeweilingen Jenkinsfile weichen allerdings von einander ab.



Jenkinsfile Service 1:

~~~groovy
node {
	stage('Checkout') {
		echo 'Checkout'
		checkout scm
		sh 'git clean -dfx'
	}

	dir('service-1') {	
		stage('Build') {
			env.PATH = "${tool 'gradle'}/bin:${env.PATH}"
			sh 'gradle build'
		}
		
		stage('Deploy') {
			httpRequest url: 'http://jenkinslab-deployserver:9090/shutdown', httpMode: 'POST', validResponseCodes: '200,408'
			sh 'scp build/libs/service-1-0.0.1-SNAPSHOT.jar jenkins@jenkinslab-deployserver:~/jenkinslab/service-1/'
			sh 'ssh matthias@jenkinslab-deployserver "nohup java -jar jenkinslab/service-1/service-1-0.0.1-SNAPSHOT.jar --server.port=9090" &'
		}
		
		stage('Healthcheck') {
			retry(5) {
				sleep time: 10, units: 'SECONDS'
				httpRequest url: 'http://jenkinslab-deployserver:9090/health', validResponseContent: '"status":"UP"'
			}
		}
	}
}
~~~

Jenkinsfile Service 2:

~~~groovy
node {
    try {
        stage('Checkout') {
            git url: 'https://github.com/comsysto/jenkins-lab-shared-pipeline.git', credentialsId: 'GITHUB_CRED'
        }

        dir('service-2') {

            stage('Build') {
                sh './gradlew clean build'
            }

            stage('Deploy') {
                sshagent(credentials: ['VAGRANT-SSH']) {
                    sh 'ssh -o StrictHostKeyChecking=no vagrant@192.168.33.10  mkdir -p service-2'
                    sh 'ssh -o StrictHostKeyChecking=no vagrant@192.168.33.10 ./service-2/shutdown.sh || true'
                    sh 'scp -o StrictHostKeyChecking=no build/libs/service-2-0.0.1-SNAPSHOT.jar startup.sh shutdown.sh vagrant@192.168.33.10:~/service-2/'
                    sh 'ssh -o StrictHostKeyChecking=no agrant@192.168.33.10 chmod -R 755 /service-2'
                    sh 'ssh -o StrictHostKeyChecking=no vagrant@192.168.33.10 ./service-2/startup.sh &'
                }
            }
        }
    } catch (exc) {
        echo "Caught: ${exc}"
        currentBuild.result = 'FAILURE'
    }
}
~~~

Die einzelnen Stages *("Checkout" -> "Build" -> "Deploy" -> "Healthcheck")* haben zwar den selben Zweck sind allerdings auf unterschiedliche Weise implementiert worden. Darunter leidet die Wartbarkeit der Pipelines und die Möglichkeit zur Wiederverwendung einzelner Schritte ist nicht gegeben. Im allgemeinen spricht man von einem Verstoß gegen das [DRY-Prinzip]().

Im folgenden wird gezeigt wie man durch extrahieren einzelner Funktionen, die als [Shared Libary](https://jenkins.io/doc/book/pipeline/shared-libraries/) in Jenkins zur Verfügung gestellt werden, Abhilfe schaffen kann.


# Schritt 1 - Extrahieren von Funktionen (Jenkins Shared Libraries)

Zuerst wird ein eigenes Repository [shared-jenkins-lib]() erstellt indem der geteilte Pipeline Code zental abgelegt wird. Die Einbiundung des Repository wird nun in Jenkins unter *Jenkins > Jenkins verwalten > System konfigurieren* im Abschnitt "Global Pipeline Libraries" konfiguriert.


* TODO Screenshot: Einrichten von Shared Libraries und Ordnerstruktur 

Die extrahierte Funktion wir als Groovy Datei im Ordner `/vars` abgelegt und mit einem sprechenden Namen versehen. Bei der Namensgebung ist zu beachten, das die *camelCase* Syntax verwendet wird. Die Implementierung erfolgt dann in der `call()` Methode. 

Die Datei `/vars/deploy.groovy` würde z.B. wiefolgt aussehen:

~~~groovy
def call(String filename, String user, String server, String port, String deploymentPath = '~/deployment/') {
    httpRequest url: "http://${server}:${port}/shutdown", httpMode: 'POST', validResponseCodes: '200,408,404'
	sh "ssh ${user}@${server} \"mkdir -p ${deploymentPath}\""
	sh "scp build/libs/${filename} ${user}@${server}:${deploymentPath}"
	sh "ssh ${user}@${server} \"nohup java -jar ${deploymentPath}/${filename} --server.port=${port}\" &"
}
~~~

In unserem Beispiel haben wir uns für implizites Laden der Library entschieden. Somit muss im Jenkinsfile selbst nicht nochmals die Library explizit geladen werden. Im Jenkinsfile kann die *Deploy* Stage nun wiefolgt vereinfacht und vereinheiltlicht werden:

~~~groovy
stage('Deploy') {
    sshagent(credentials: [jenkinsSshCredentialsId]) {
        deploy('service-1-0.0.1-SNAPSHOT.jar', 'jenkins', 'http://jenkinslab-deployserver', '9090', '~/')
    }
}
~~~

Analog können weitere Teile der Pipeline wie z.B. *Checkout*, *Build* & *Healthcheck* als eigene Funktionen ausgelagert werden.

# Schritt 2 - Vereinfachen der Pipeline mit Hilfe einer strukturierten DSL 

>If you have a lot of Pipelines that are mostly similar, the global variable mechanism provides a handy tool to build a higher-level DSL that captures the similarity. [Jenkins Documentation]

Hat man viele solcher Spring-Boot Services, steigt der Wunsch die Pipelines noch weiter zu vereinfachen. Durch den Einsatz von globalen Variablen und Closures kann die Pipeline DSL einfach erweitert werden. Das Jenkinsfile selbst kann somit auf die Deklaration service-spezififischer Variablen reduziert werden und die gesamte Logik wandert ins zentrale *"shared-jenkins-lib"* Repository.

In unserem Fall haben wir eine `springBootPipeline` eingeführt die wiefolgt im Jenkinsfile von Service 2 verwendet werden kann:

~~~groovy
springBootPipeline {
    hostname = env.DEPLOYMENT_SERVER_HOSTNAME
    username = env.DEPLOYMENT_USER
    port = '9092'
    filename = 'service-2-0.0.1-SNAPSHOT.jar'
    servicename = 'service-2'
}
~~~

Die Implementierung liegt im zentralen Repository in der Datei `/vars/springBootPipeline.groovy` und sieht folgendermaßen aus:

~~~groovy
def call(Closure body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    node {
        try {
            stage('Checkout') {
                checkout scm
                sh 'git clean -dfx'
            }

            dir(config.servicename) {
                stage('Build') {
                    sh './gradlew clean build'
                }

                stage('Deploy') {
                    deploy(config.filename, config.username, config.hostname, config.port)
                }

                stage('Healthcheck') {
                    healthcheck("http://${config.hostname}:${config.port}/health")
                }
            }
        }
        catch (exc) {
            echo "Caught: ${exc}"
            currentBuild.result = 'FAILURE'
        }
    }
}
~~~


# Fazit

* Super einfach
* DRY


# Referenzen
* github repo
* siehe Trello Karte




# Allgemeine Punkte
???

* Versioning + Explizites Laden
* Hinweis: In der Praxis filename nicht fix 

