$TTL 604800
@   IN  SOA ns.uliege.be. admin.uliege.be. (
        2025110601 ; Serial  (YYYYMMDDnn)
        604800     ; Refresh
        86400      ; Retry
        2419200    ; Expire
        604800 )   ; Negative Cache TTL
;

@               IN  NS   ns.uliege.be.
ns              IN  A    10.0.1.2
mail            IN  A    10.0.1.7
@               IN  MX   10 mail.uliege.be.

gembloux.uliege.be. IN MX 10 mail.gembloux.uliege.be.
mail.gembloux.uliege.be. IN A 10.0.2.7

info.uliege.be. IN MX 10 mail.uliege.be.
